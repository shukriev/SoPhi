package dev.sophi.core.agent

import com.charleskorn.kaml.Yaml
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import kotlinx.serialization.Serializable
import java.nio.file.Path

data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val allowedTools: List<String>,
    val model: String? = null,
    /** Deny and ask rules this agent's loop adds to its parent's; it can never loosen them. */
    val rules: List<PermissionRule> = emptyList(),
)

@Serializable
private data class AgentDefinitionMetadata(
    val name: String,
    val description: String,
    val allowedTools: List<String> = emptyList(),
    val model: String? = null,
    val rules: List<PermissionRule> = emptyList(),
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
    val (allows, rules) = metadata.rules.partition { it.effect == Effect.ALLOW }
    if (allows.isNotEmpty()) System.err.println("warning: $source: an agent can only tighten rules; ignoring ${allows.size} allow rule(s)")
    return AgentDefinition(
        name = metadata.name,
        description = metadata.description,
        systemPrompt = body,
        allowedTools = metadata.allowedTools,
        model = metadata.model,
        rules = rules,
    )
}
