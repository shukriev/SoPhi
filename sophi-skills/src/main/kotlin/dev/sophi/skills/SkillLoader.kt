package dev.sophi.skills

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class SkillLoader {
    fun load(directory: Path): List<Skill> {
        require(directory.isDirectory()) { "Not a directory: $directory" }
        return directory.listDirectoryEntries("*.md")
            .filter { it.isRegularFile() }
            .map { loadFile(it) }
            .sortedBy { it.metadata.title }
    }

    fun loadFile(file: Path): Skill {
        val content = file.readText()
        val (metadata, body) = parseFrontmatter(content)
        return Skill(metadata = metadata, body = body, source = file)
    }
}
