package dev.sophi.skills

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

class SkillRegistry(private val skills: Map<String, Skill>) {
    fun get(id: String): Skill? = skills[id]

    fun all(): List<Pair<String, Skill>> =
        skills.entries.map { it.key to it.value }.sortedBy { it.first }

    companion object {
        fun load(globalDir: Path, projectDir: Path, loader: SkillLoader = SkillLoader()): SkillRegistry {
            val global = loadDirTolerant(globalDir, loader)
            val project = loadDirTolerant(projectDir, loader)
            return SkillRegistry(global + project) // project entries win on id collision
        }

        private fun loadDirTolerant(dir: Path, loader: SkillLoader): Map<String, Skill> {
            if (!dir.isDirectory()) return emptyMap()
            return dir.listDirectoryEntries("*.md")
                .filter { it.isRegularFile() }
                .mapNotNull { file ->
                    runCatching { loader.loadFile(file) }.getOrNull()
                        ?.let { file.nameWithoutExtension to it }
                }
                .toMap()
        }
    }
}
