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
    val rejected: Map<String, List<String>> = emptyMap()
)

data class DiscoveredSkill(val id: String, val path: Path, val skillMdPath: Path)

private fun Path.walkSkillDirs(): List<DiscoveredSkill> =
    toFile().walkTopDown()
        .filter { it.isFile && it.name == "SKILL.md" }
        .map { DiscoveredSkill(id = it.parentFile.name, path = it.parentFile.toPath(), skillMdPath = it.toPath()) }
        .toList()

@Serializable
private data class ClaudeSkillFrontmatter(val name: String? = null, val description: String = "")

private val permissiveYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

class SkillInstaller {
    fun install(
        source: String,
        targetDir: Path,
        only: Set<String> = emptySet(),
        validate: (id: String, content: String) -> List<String> = { _, _ -> emptyList() }
    ): InstallResult {
        targetDir.createDirectories()
        val (root, cleanup) = resolveSource(source)
        try {
            val discovered = root.walkSkillDirs().sortedBy { it.id }
            val selected = if (only.isEmpty()) discovered else discovered.filter { it.id in only }
            val notFound = (only - selected.map { it.id }.toSet()).sorted()
            val installed = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val rejected = mutableMapOf<String, List<String>>()
            selected.forEach { skillDir ->
                if (targetDir.resolve("${skillDir.id}.md").exists()) {
                    skipped += skillDir.id
                } else {
                    val rawContent = skillDir.skillMdPath.readText()
                    val violations = validate(skillDir.id, rawContent)
                    if (violations.isNotEmpty()) {
                        rejected[skillDir.id] = violations
                    } else {
                        installSkill(skillDir, targetDir)
                        installed += skillDir.id
                    }
                }
            }
            return InstallResult(installed, skipped, notFound, rejected)
        } finally {
            cleanup()
        }
    }

    fun remove(targetDir: Path, id: String): Boolean {
        val mdFile = targetDir.resolve("$id.md")
        if (!mdFile.exists()) return false
        mdFile.deleteExisting()
        targetDir.resolve(id).takeIf { it.isDirectory() }?.toFile()?.deleteRecursively()
        return true
    }

    private fun resolveSource(source: String): Pair<Path, () -> Unit> {
        if (source.startsWith("http://") || source.startsWith("https://") ||
            source.startsWith("git@") || source.startsWith("file://")
        ) {
            val tempDir = createTempDirectory("skill-install")
            val process = ProcessBuilder("git", "clone", "--depth", "1", source, tempDir.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git clone failed: $output" }
            return tempDir to { tempDir.toFile().deleteRecursively(); Unit }
        }
        return Path.of(source) to {}
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
        }
    }
}
