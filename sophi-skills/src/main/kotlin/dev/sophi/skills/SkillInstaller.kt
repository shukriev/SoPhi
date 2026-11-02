package dev.sophi.skills

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class InstallResult(
    val installed: List<String>,
    val skipped: List<String>,
    val notFound: List<String>,
    val rejected: Map<String, List<String>> = emptyMap(),
    /** Scan findings per skill, whether the skill went in (confirmed) or was held back. */
    val findings: Map<String, List<ScanFinding>> = emptyMap()
)

data class DiscoveredSkill(val id: String, val path: Path, val skillMdPath: Path)

private fun Path.walkSkillDirs(): List<DiscoveredSkill> =
    toFile().walkTopDown()
        .filter { it.isFile && it.name == "SKILL.md" }
        .map { DiscoveredSkill(id = it.parentFile.name, path = it.parentFile.toPath(), skillMdPath = it.toPath()) }
        .toList()

@Serializable
internal data class ClaudeSkillFrontmatter(val name: String? = null, val description: String = "")

internal val permissiveYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

private const val BUNDLE_MARKER = ".sophi-bundle"

/**
 * Installs Claude Code-shaped skills from a local path or git URL (`url#ref` checks out a branch,
 * tag or commit). Every install is pinned in [SkillLock]. An id that is already there is skipped
 * unless `update` asks to replace it, which also moves its pin. Each skill is scanned first
 * ([scanSkill]); one with findings goes in only when `confirm` says yes.
 */
class SkillInstaller {
    fun install(
        source: String,
        targetDir: Path,
        only: Set<String> = emptySet(),
        update: Boolean = false,
        confirm: (id: String, findings: List<ScanFinding>) -> Boolean = { _, _ -> false },
        validate: (id: String, content: String) -> List<String> = { _, _ -> emptyList() }
    ): InstallResult {
        targetDir.createDirectories()
        val (root, commit, cleanup) = resolveSource(source)
        try {
            val discovered = root.walkSkillDirs().sortedBy { it.id }
            val selected = if (only.isEmpty()) discovered else discovered.filter { it.id in only }
            val notFound = (only - selected.map { it.id }.toSet()).sorted()
            val installed = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val rejected = mutableMapOf<String, List<String>>()
            val findings = mutableMapOf<String, List<ScanFinding>>()
            selected.forEach { skillDir ->
                if (!update && targetDir.resolve("${skillDir.id}.md").exists()) {
                    skipped += skillDir.id
                    return@forEach
                }
                val violations = validate(skillDir.id, skillDir.skillMdPath.readText())
                if (violations.isNotEmpty()) {
                    rejected[skillDir.id] = violations
                    return@forEach
                }
                val found = scanSkill(skillDir.path)
                if (found.isNotEmpty()) findings[skillDir.id] = found
                if (found.isNotEmpty() && !confirm(skillDir.id, found)) return@forEach
                remove(targetDir, skillDir.id)
                installSkill(skillDir, targetDir)
                val pin = SkillPin(source, commit, skillContentHash(targetDir, skillDir.id))
                SkillLock.write(targetDir, SkillLock.read(targetDir) + (skillDir.id to pin))
                installed += skillDir.id
            }
            return InstallResult(installed, skipped, notFound, rejected, findings)
        } finally {
            cleanup()
        }
    }

    fun remove(targetDir: Path, id: String): Boolean {
        val mdFile = targetDir.resolve("$id.md")
        if (!mdFile.exists()) return false
        mdFile.deleteExisting()
        val bundleDir = targetDir.resolve(id)
        if (bundleDir.isDirectory() && bundleDir.resolve(BUNDLE_MARKER).exists()) {
            bundleDir.toFile().deleteRecursively()
        }
        SkillLock.read(targetDir).let { if (id in it) SkillLock.write(targetDir, it - id) }
        return true
    }

    /** The folder to install from, the commit it is at (null for a local path), and its cleanup. */
    private fun resolveSource(source: String): Triple<Path, String?, () -> Unit> {
        if (source.startsWith("http://") || source.startsWith("https://") ||
            source.startsWith("git@") || source.startsWith("file://")
        ) {
            val url = source.substringBefore('#')
            val ref = source.substringAfter('#', "").ifEmpty { null }
            val tempDir = createTempDirectory("skill-install")
            try {
                // A ref may be a commit, which a shallow clone can't check out, so a ref clones in full.
                if (ref == null) git("clone", "--depth", "1", url, tempDir.toString(), what = "git clone")
                else {
                    git("clone", url, tempDir.toString(), what = "git clone")
                    git("-C", tempDir.toString(), "checkout", "-q", ref, what = "git checkout $ref")
                }
                val commit = git("-C", tempDir.toString(), "rev-parse", "HEAD", what = "git rev-parse")
                return Triple(tempDir, commit) { tempDir.toFile().deleteRecursively(); Unit }
            } catch (e: IllegalStateException) {
                tempDir.toFile().deleteRecursively()
                throw e
            }
        }
        return Triple(Path.of(source), null) {}
    }

    private fun git(vararg args: String, what: String): String {
        val process = ProcessBuilder("git", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "$what failed: $output" }
        return output.trim()
    }

    private fun installSkill(skillDir: DiscoveredSkill, targetDir: Path) {
        val content = skillDir.skillMdPath.readText()
        val (yaml, body) = splitFrontmatter(content)
        val fm = runCatching {
            permissiveYaml.decodeFromString(ClaudeSkillFrontmatter.serializer(), yaml)
        }.getOrElse { ClaudeSkillFrontmatter() }

        val siblings = skillDir.path.listDirectoryEntries().filter { it.name != "SKILL.md" }
        val bundleNote = if (siblings.isEmpty()) "" else
            "\n> Bundled files for this skill: ${targetDir.resolve(skillDir.id).toAbsolutePath()}\n"

        val normalized = """
            |---
            |title: ${fm.name ?: skillDir.id}
            |description: ${fm.description}
            |---
            |$bundleNote
            |$body
        """.trimMargin()
        targetDir.resolve("${skillDir.id}.md").writeText(normalized)

        if (siblings.isNotEmpty()) {
            val bundleDir = targetDir.resolve(skillDir.id).also { it.createDirectories() }
            siblings.forEach { it.toFile().copyRecursively(bundleDir.resolve(it.name).toFile()) }
            bundleDir.resolve(BUNDLE_MARKER).writeText("")
        }
    }
}
