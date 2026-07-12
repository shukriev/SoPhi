package dev.sophi.learning.export

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.FileSessionManager
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.PreferenceRecord
import dev.sophi.learning.PreferenceStore
import dev.sophi.learning.SessionOutcome
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Read-only fixture layer for the offline export pipeline (Phase 4). Pure
 * batch reads over the phase-1/phase-2 JSONL logs and session store — no
 * LLM/provider imports here, per ADR-012.
 */
class ExportInputs(learningHome: Path, sessionsDir: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val outcomesLog = JsonlLog(learningHome.resolve("session-outcomes.jsonl"))
    private val prefStore = PreferenceStore(JsonlLog(learningHome.resolve("preferences.jsonl")))
    private val sessionManager = FileSessionManager(sessionsDir)

    fun judgedOutcomes(): Map<String, SessionOutcome> =
        outcomesLog.readAll()
            .mapNotNull { runCatching { json.decodeFromString(SessionOutcome.serializer(), it) }.getOrNull() }
            .associateBy { it.sessionId }   // last line per session wins

    fun activePreferences(): List<PreferenceRecord> =
        prefStore.activeAll()

    fun negativeSessions(): Set<String> =
        activePreferences().filter { it.polarity == "negative" }.map { it.sessionId }.toSet()

    fun dpoLinks(): List<Pair<PreferenceRecord, PreferenceRecord>> {
        val bySession = activePreferences().groupBy { it.sessionId }
        return bySession.values.flatMap { records ->
            records.filter { it.polarity == "negative" && it.pairedWith != null }
                .mapNotNull { neg ->
                    records.find { it.polarity == "positive" && it.id == neg.pairedWith }
                        ?.let { pos -> neg to pos }
                }
        }
    }

    fun loadSession(sessionId: String): AgentSession? =
        runCatching { sessionManager.load(sessionId) }.getOrNull()

    fun configSnapshot(sessionId: String): Pair<String?, String?> =
        sessionManager.readConfigSnapshot(sessionId)

    /** One pass over [FileSessionManager.list] to collect all subagent session ids. */
    fun subagentSessionIds(): Set<String> =
        sessionManager.list().filter { it.parentSessionId != null }.map { it.id }.toSet()

    @Deprecated(
        "O(n) list() scan per call; use subagentSessionIds() once and check membership instead",
        ReplaceWith("subagentSessionIds().contains(sessionId)")
    )
    fun isSubagent(sessionId: String): Boolean =
        subagentSessionIds().contains(sessionId)
}
