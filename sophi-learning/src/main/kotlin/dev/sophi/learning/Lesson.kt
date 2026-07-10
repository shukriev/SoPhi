package dev.sophi.learning

import kotlinx.serialization.Serializable

@Serializable
data class Lesson(
    val id: String,                  // "les_" + UUID
    val ts: Long,
    val scope: String,               // "*" = global
    val sessionId: String,
    val text: String,
    val kind: String,                // tool_usage|environment|approach|user_context|preference
    val useCount: Int = 0,
    val status: String = "active"    // active|archived
)
