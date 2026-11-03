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

    /**
     * Another agent's `<name>/SKILL.md` (Claude Code, `.agents`, agentskills.io): `name` and
     * `description` are read, every other key (`allowed-tools`, ...) is ignored, so it gets no
     * more trust than a SoPhi skill. Bundled files are only pointed at, never run. Throws on
     * frontmatter that doesn't parse.
     */
    fun loadSkillMd(file: Path): Skill {
        val (yaml, body) = splitFrontmatter(file.readText())
        val fm = if (yaml.isEmpty()) ClaudeSkillFrontmatter()
            else permissiveYaml.decodeFromString(ClaudeSkillFrontmatter.serializer(), yaml)
        val dir = file.parent
        val bundled = dir.listDirectoryEntries().any { it.fileName.toString() != "SKILL.md" }
        val note = if (bundled) "> Bundled files for this skill: ${dir.toAbsolutePath()}\n\n" else ""
        return Skill(
            metadata = SkillMetadata(title = fm.name ?: dir.fileName.toString(), description = fm.description),
            body = note + body,
            source = file
        )
    }
}
