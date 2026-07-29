package dev.sophi.skills

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class SkillMetadata(
    val title: String,
    val description: String = "",
    val version: String = "1.0.0",
    val tags: List<String> = emptyList()
)

data class Skill(
    val metadata: SkillMetadata,
    val body: String,
    val source: Path
)

internal fun splitFrontmatter(content: String): Pair<String, String> {
    if (!content.startsWith("---\n")) return Pair("", content)
    val lines = content.lines()
    // lines[0] == "---"; find the next standalone "---" line
    val closeIdx = lines.drop(1).indexOfFirst { it == "---" }
    if (closeIdx < 0) return Pair("", content)
    val yaml = lines.drop(1).take(closeIdx).joinToString("\n")
    val body = lines.drop(closeIdx + 2).joinToString("\n").trimStart('\n')
    return Pair(yaml, body)
}

internal fun parseFrontmatter(content: String): Pair<SkillMetadata, String> {
    val (yaml, body) = splitFrontmatter(content)
    if (yaml.isEmpty()) return Pair(SkillMetadata(title = "Untitled"), content)
    val metadata = Yaml.default.decodeFromString(SkillMetadata.serializer(), yaml)
    return Pair(metadata, body)
}
