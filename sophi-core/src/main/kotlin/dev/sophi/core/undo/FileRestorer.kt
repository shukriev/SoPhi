package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class RestorePlan(
    /** Earliest round being undone; the marker's `restoredTo`. */
    val targetEntryId: String,
    /** The earliest checkpoint among the rounds being undone. */
    val checkpoint: String?,
    /** In the order to reverse them: newest first. */
    val moves: List<FileMove>,
    /** From [CheckpointStore.diffStat]: what the restore changes. "" without a checkpoint. */
    val diffStat: String,
    /** Bash commands of these rounds that reached outside the workspace, and nested repos: not undone. */
    val warnings: List<String>,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Undo and revert over a session's tool rounds. A round is an entry with `checkpoint` or `moves`
 * metadata; a marker (`restoredTo`) hides its target and every round after it.
 */
class FileRestorer(private val checkpoints: CheckpointStore?) {

    /** Rounds on the branch not yet undone, newest first. A marker whose target is on another branch is ignored. */
    private fun effectiveRounds(branch: List<SessionEntry>): List<SessionEntry> {
        val ids = branch.mapTo(HashSet()) { it.id }
        var skipTo: String? = null
        val out = mutableListOf<SessionEntry>()
        for (e in branch.asReversed()) {
            val target = e.metadata["restoredTo"]
            when {
                skipTo != null -> if (e.id == skipTo) skipTo = null
                target != null -> if (target in ids) skipTo = target
                "checkpoint" in e.metadata || "moves" in e.metadata -> out += e
            }
        }
        return out
    }

    /** [entryId] null = undo the last round not yet undone; else revert every such round at or after that entry. Null when there is nothing to restore. */
    fun plan(session: AgentSession, entryId: String? = null): RestorePlan? {
        val branch = session.branch()
        val effective = effectiveRounds(branch)
        val rounds = if (entryId == null) effective.take(1) else {
            val index = branch.withIndex().associate { (i, e) -> e.id to i }
            val at = requireNotNull(index[entryId]) { "Entry not found: $entryId" }
            effective.takeWhile { index.getValue(it.id) >= at }
        }
        if (rounds.isEmpty()) return null
        val checkpoint = rounds.lastOrNull { "checkpoint" in it.metadata }?.metadata?.get("checkpoint")
        val diffStat = checkpoint?.let { checkpoints?.diffStat(it) }.orEmpty()
        // after diffStat: its snapshot is the "now" nestedRepos reads
        val nested = checkpoint?.let { checkpoints?.nestedRepos(it) }.orEmpty().map { "changes inside nested repo $it" }
        return RestorePlan(
            targetEntryId = rounds.last().id,
            checkpoint = checkpoint,
            moves = rounds.flatMap { r -> r.metadata["moves"]?.let { json.decodeFromString<List<FileMove>>(it) }.orEmpty().asReversed() },
            diffStat = diffStat,
            warnings = rounds.flatMap { r -> r.metadata["outsideCommands"]?.let { json.decodeFromString<List<String>>(it) }.orEmpty() } + nested,
        )
    }

    /**
     * Reverses the moves, restores the checkpoint, appends the marker entry (`restoredFrom` = the pre-restore snapshot).
     * Returns what it skipped, one line each; the restore leaves a skipped move's paths alone. Moves go first so they
     * stay reversed even when the restore throws. The caller saves the session.
     */
    fun apply(session: AgentSession, plan: RestorePlan): List<String> {
        val skipped = mutableListOf<String>()
        val keep = mutableListOf<Path>()
        for (m in plan.moves) {
            val from = Path.of(m.from)
            val to = Path.of(m.to)
            val reason = when {
                !to.exists() -> "${m.to} no longer exists"
                from.exists() -> "${m.from} exists again"
                else -> runCatching { from.parent?.createDirectories(); Files.move(to, from) }.exceptionOrNull()?.let { it.message ?: it.toString() }
            }
            if (reason != null) {
                skipped += "Skipped ${m.to} → ${m.from}: $reason"
                keep += listOf(from, to).filter { it.exists() }
            }
        }
        val restoredFrom = if (checkpoints != null && plan.checkpoint != null) checkpoints.restore(plan.checkpoint, keep) else null
        session.append(EntryRole.ASSISTANT, "Files restored to before entry ${plan.targetEntryId}.",
            mapOf("replay" to "false", "restoredTo" to plan.targetEntryId) + listOfNotNull(restoredFrom?.let { "restoredFrom" to it }))
        return skipped
    }
}
