package dev.sophi.core.context

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val lenientJson = Json { ignoreUnknownKeys = true }

class ContextCompactor(
    private val provider: LLMProvider,
    private val summariser: Summariser = Summariser(provider, CompactionMode.STRUCTURED, 32_000, dev.sophi.ai.system1.Judgments.COMPACTOR)
) {

    /**
     * Replayed size against half the window, or too many replayed entries; non-replayed tool rounds
     * never count. False whenever [compact] would be a no-op, so a turn never pays for a pointless call.
     */
    fun needsCompaction(session: AgentSession, config: AgentConfig, windowTokens: Int, keepRecentCount: Int = 4): Boolean {
        val replayable = session.branch().filter { it.metadata["replay"] != "false" }
        if (cutIndex(replayable, keepRecentCount) <= 0) return false
        return replayable.sumOf { it.content.length } / 4 > windowTokens * 0.5 || replayable.size > config.maxBranchLength
    }

    /** Start of the kept tail, moved back to a USER entry (a strict template rejects a leading ASSISTANT); <= 0 = nothing to do. */
    private fun cutIndex(replayable: List<SessionEntry>, keepRecentCount: Int): Int {
        if (replayable.size <= keepRecentCount) return 0
        var cut = replayable.size - keepRecentCount
        while (cut > 0 && replayable[cut].role != EntryRole.USER) cut--
        return cut
    }

    suspend fun compact(
        session: AgentSession,
        config: AgentConfig,
        keepRecentCount: Int = 4,
        onUsage: (dev.sophi.ai.api.TokenUsage) -> Unit = {}
    ): AgentSession {
        val entries = session.branch()
        // replay=false entries (e.g. persisted tool rounds) are inert for prompting; keep them in
        // the DAG but exclude them from the transcript and the keep/compact split so the summary
        // never leaks raw tool output and keepRecentCount counts real conversation entries.
        val replayable = entries.filter { it.metadata["replay"] != "false" }
        // The kept tail must open on a USER entry: the summary pair ends on ASSISTANT.
        val cut = cutIndex(replayable, keepRecentCount)
        if (cut <= 0) return session

        val toCompact = replayable.subList(0, cut)
        val toKeep = replayable.subList(cut, replayable.size)
        val firstKeptId = toKeep.first().id

        val transcript = toCompact.joinToString("\n") { entry -> "${entry.role.name}: ${entry.content}" }
        // The WorkingSet reads the persisted tool rounds too, so exact files/commands survive the cut.
        // A previous cross-turn summary carries its own WorkingSet (the rounds it covered are gone from the branch).
        val previous = entries.firstOrNull()?.takeIf { it.metadata["_compacted"] == "true" }
            ?.metadata?.get("_workingSet")?.let { runCatching { lenientJson.decodeFromString<WorkingSet>(it) }.getOrNull() }
        val fresh = WorkingSet.fromEntries(entries.takeWhile { it.id != firstKeptId })
        val workingSet = previous?.merge(fresh) ?: fresh
        val summary = summariser.summarise(transcript, workingSet, config.model, onUsage)

        // Replace only the compacted prefix of the active branch: the summary pair becomes a new
        // root and the first kept entry is re-parented onto it. Entries on other branches
        // (and the compacted prefix itself) stay in the DAG untouched.
        val now = System.currentTimeMillis()
        val summaryEntry = SessionEntry(
            id = UUID.randomUUID().toString(),
            parentId = null,
            role = EntryRole.USER,
            content = "Summary of the conversation so far:\n$summary",
            timestamp = now,
            metadata = mapOf("_compacted" to "true", "_workingSet" to Json.encodeToString(workingSet))
        )
        val ackEntry = SessionEntry(
            id = UUID.randomUUID().toString(),
            parentId = summaryEntry.id,
            role = EntryRole.ASSISTANT,
            content = "Understood — continuing from that summary.",
            timestamp = now,
            metadata = mapOf("_compacted" to "true")
        )
        val rewritten = session.entries.map { entry ->
            if (entry.id == firstKeptId) entry.copy(parentId = ackEntry.id) else entry
        }
        return AgentSession(
            id = session.id,
            title = session.title,
            parentSessionId = session.parentSessionId,
            initialEntries = listOf(summaryEntry, ackEntry) + rewritten,
            initialTipId = session.tip?.id
        )
    }
}
