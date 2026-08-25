package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.skills.SkillRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SkillArgs(val name: String? = null)

class SkillTool(private val registry: SkillRegistry) : Tool {
    override val name = "skill"
    override val description: String =
        "Load a skill's instructions into context. Available skills:\n" +
            registry.all().joinToString("\n") { (id, skill) -> "- $id: ${skill.metadata.description}" }
    override val parametersJson = """
        {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString(SkillArgs.serializer(), argumentsJson) }.getOrNull()
        val skillName = args?.name ?: return "Error: missing 'name' argument"
        return registry.get(skillName)?.body ?: "Error: skill not found: $skillName"
    }
}
