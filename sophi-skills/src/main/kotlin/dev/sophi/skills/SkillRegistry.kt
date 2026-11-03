package dev.sophi.skills

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** [refused] says, one line per skill, which pinned skills were left out because their files no longer match the pin. */
class SkillRegistry(private val skills: Map<String, Skill>, val refused: List<String> = emptyList()) {
    fun get(id: String): Skill? = skills[id]

    fun filter(keep: (String) -> Boolean): SkillRegistry = SkillRegistry(skills.filterKeys(keep), refused)

    fun all(): List<Pair<String, Skill>> =
        skills.entries.map { it.key to it.value }.sortedBy { it.first }

    /** Standalone root-level skills and domain-root skills only — excludes domain members.
     *  This is what SkillTool's agent-facing description is built from, so it stays bounded
     *  by domain count rather than total skill count. */
    fun topLevel(): List<Pair<String, Skill>> =
        skills.entries.filter { '/' !in it.key }
            .map { it.key to it.value }.sortedBy { it.first }

    /** A domain root's members, for SkillTool's generated child listing. Empty if [domainId]
     *  isn't a domain (or has no members yet). */
    fun childrenOf(domainId: String): List<Pair<String, Skill>> =
        skills.entries.filter { it.key.startsWith("$domainId/") }
            .map { it.key to it.value }.sortedBy { it.first }

    companion object {
        fun load(globalDir: Path, projectDir: Path, loader: SkillLoader = SkillLoader()): SkillRegistry {
            val refused = mutableListOf<String>()
            val global = loadDirTolerant(globalDir, loader, refused)
            val project = loadDirTolerant(projectDir, loader, refused)
            return SkillRegistry(global + project, refused) // project entries win on id collision
        }

        private fun loadDirTolerant(dir: Path, loader: SkillLoader, refused: MutableList<String>): Map<String, Skill> {
            if (!dir.isDirectory()) return emptyMap()
            val pins = SkillLock.read(dir)
            val flat = dir.listDirectoryEntries("*.md")
                .filter { it.isRegularFile() }
                .filter { file ->
                    val id = file.nameWithoutExtension
                    val pin = pins[id] ?: return@filter true
                    (runCatching { skillContentHash(dir, id) }.getOrNull() == pin.sha256).also { ok ->
                        if (!ok) refused += "Skill '$id' in $dir was not loaded: its files changed since it was " +
                            "installed from ${pin.source}. Reinstall it with --update, or remove it."
                    }
                }
                .mapNotNull { file ->
                    runCatching { loader.loadFile(file) }.getOrNull()
                        ?.let { file.nameWithoutExtension to it }
                }
                .toMap()
            // A subdirectory with a same-named flat sibling is installer-owned bundle content
            // (SkillInstaller always creates exactly this pairing) — never scanned as a domain.
            val domains = dir.listDirectoryEntries()
                .filter { it.isDirectory() && !dir.resolve("${it.name}.md").isRegularFile() }
                .flatMap { domainDir -> loadDomainDir(domainDir, loader) }
                .toMap()
            return flat + domains
        }

        private fun loadDomainDir(domainDir: Path, loader: SkillLoader): List<Pair<String, Skill>> =
            domainDir.listDirectoryEntries("*.md")
                .filter { it.isRegularFile() }
                .mapNotNull { file ->
                    runCatching { loader.loadFile(file) }.getOrNull()?.let { skill ->
                        val id = if (file.nameWithoutExtension == "_index") domainDir.name
                            else "${domainDir.name}/${file.nameWithoutExtension}"
                        id to skill
                    }
                }
    }
}
