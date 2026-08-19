package dev.sophi.schedule.tools

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import dev.sophi.schedule.model.Proposal
import dev.sophi.schedule.store.ProposalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ProposeImprovementArgs(
    val title: String,
    val category: String,
    val rationale: String,
    val suggestedAction: String
)

class ProposalPlugin(private val store: ProposalStore) : SophiPlugin {
    override val name = "proposals"
    private val json = Json { ignoreUnknownKeys = true }

    override fun hooks(): List<AgentHook> = listOf(
        object : AgentHook {
            override val point = HookPoint.BEFORE_TOOL
            override suspend fun invoke(context: HookContext) {
                if (context.toolName != "propose_improvement") return
                val argsJson = context.argumentsJson ?: return
                runCatching {
                    val args = json.decodeFromString(ProposeImprovementArgs.serializer(), argsJson)
                    store.add(Proposal(
                        sessionId = context.sessionId,
                        title = args.title,
                        category = args.category,
                        rationale = args.rationale,
                        suggestedAction = args.suggestedAction
                    ))
                }
            }
        }
    )
}
