package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.skills.SkillRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SkillArgs(val name: String? = null)

/** [load] runs on every use, so a skill written mid-session (e.g. an auto-learned one) shows up at
 *  once. [topK] caps how many skills are listed in [description] — a HarnessConfig knob;
 *  `null` (the default) lists every skill. */
class SkillTool(private val load: () -> SkillRegistry, private val topK: Int? = null) : Tool {
    constructor(registry: SkillRegistry, topK: Int? = null) : this({ registry }, topK)

    override val name = "skill"
    override val description: String get() =
        "Load a skill's instructions into context. Available skills:\n" +
            load().topLevel().let { all -> topK?.let { all.take(it) } ?: all }
                .joinToString("\n") { (id, skill) -> "- $id: ${skill.metadata.description}" }
    override val parametersJson = """
        {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString(SkillArgs.serializer(), argumentsJson) }.getOrNull()
        val skillName = args?.name ?: return "Error: missing 'name' argument"
        val registry = load()
        val skill = registry.get(skillName) ?: return "Error: skill not found: $skillName"
        val children = registry.childrenOf(skillName)
        if (children.isEmpty()) return skill.body
        return skill.body + "\n\nAvailable in this domain:\n" +
            children.joinToString("\n") { (id, child) -> "- $id: ${child.metadata.description}" }
    }
}
