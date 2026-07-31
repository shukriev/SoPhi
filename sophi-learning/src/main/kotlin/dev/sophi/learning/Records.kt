package dev.sophi.learning

import kotlinx.serialization.Serializable

@Serializable
data class ToolEvent(
    val ts: Long,
    val scope: String,
    val sessionId: String,
    val tool: String,
    val success: Boolean,
    val durationMillis: Long,
    val errorSnippet: String? = null
)

@Serializable
data class SessionOutcome(
    val ts: Long,
    val scope: String,
    val sessionId: String,
    val outcome: String,            // "open" | "completed" | "error"
    val turns: Int = 0,
    val toolCalls: Int = 0,
    val toolErrors: Int = 0,
    val model: String? = null,
    val judgment: String? = null,   // Phase 2: "success" | "partial" | "failure"
    val reason: String? = null,     // Phase 2
    val planningNote: String? = null // set when this session was plan-driven (ADR-018): which
                                      // step(s) needed replanning and why, so the evaluator can
                                      // emit "planning"-kind lessons, not just conversational ones
)

@Serializable
data class PreferenceRecord(
    val id: String,                 // "pref_" + UUID
    val ts: Long,
    val scope: String,
    val sessionId: String,
    val entryIndex: Int,            // index into the session's entry list
    val polarity: String,           // "positive" | "negative"
    val source: String,             // "explicit" | "implicit"
    val reason: String? = null,     // explicit
    val signal: String? = null,     // implicit: user_corrected|user_rephrased|user_frustrated|user_satisfied
    val evidence: String? = null,   // implicit: verbatim user quote (required)
    val weight: Double = 1.0,
    val pairedWith: String? = null, // id of the retry-chain partner record
    val status: String = "active"   // active | deleted
)
