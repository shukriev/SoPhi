package dev.sophi.core.agent

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Path

data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val allowedTools: List<String>,
    val model: String? = null
)

@Serializable
private data class AgentDefinitionMetadata(
    val name: String,
    val description: String,
    val allowedTools: List<String> = emptyList(),
    val model: String? = null
)

internal fun parseAgentDefinition(content: String, source: Path): AgentDefinition {
    require(content.startsWith("---\n")) {
        "Agent definition file is missing YAML frontmatter: $source"
    }
    val lines = content.lines()
    val closeIdx = lines.drop(1).indexOfFirst { it == "---" }
    require(closeIdx >= 0) {
        "Agent definition file is missing closing '---' delimiter: $source"
    }
    val yaml = lines.drop(1).take(closeIdx).joinToString("\n")
    val body = lines.drop(closeIdx + 2).joinToString("\n").trim()
    require(body.isNotEmpty()) {
        "Agent definition file has no system prompt body: $source"
    }
    val metadata = Yaml.default.decodeFromString(AgentDefinitionMetadata.serializer(), yaml)
    return AgentDefinition(
        name = metadata.name,
        description = metadata.description,
        systemPrompt = body,
        allowedTools = metadata.allowedTools,
        model = metadata.model
    )
}
