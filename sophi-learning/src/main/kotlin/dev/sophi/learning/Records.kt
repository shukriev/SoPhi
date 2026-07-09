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
    val reason: String? = null      // Phase 2
)
