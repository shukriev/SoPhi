package dev.sophi.sdk

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import dev.sophi.skills.SkillInvocationEvent
import dev.sophi.skills.SkillInvocationStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SkillReadArgs(val name: String? = null)

/**
 * Only `skill` (read) calls are recorded — the interesting signal is downstream use of a skill's
 * guidance, not write_skill's own immediate success (WriteSkillTool already verifies that itself
 * by re-parsing before returning).
 */
class SkillInvocationPlugin(private val store: SkillInvocationStore) : SophiPlugin {
    override val name = "skill-invocations"
    private val json = Json { ignoreUnknownKeys = true }

    override fun hooks(): List<AgentHook> = listOf(
        object : AgentHook {
            override val point = HookPoint.BEFORE_TOOL
            override suspend fun invoke(context: HookContext) {
                if (context.toolName != "skill") return
                val argsJson = context.argumentsJson ?: return
                runCatching {
                    val args = json.decodeFromString(SkillReadArgs.serializer(), argsJson)
                    val skillId = args.name ?: return
                    store.record(SkillInvocationEvent(sessionId = context.sessionId, skillId = skillId))
                }
            }
        }
    )
}
