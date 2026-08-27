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
    val status: String = "active",   // active|archived
    /** Short, normalized situation/failure-class signature (e.g. "maven-module-not-targeted"),
     *  distinct from [text]'s free-form content — a second retrieval axis alongside [scope],
     *  letting similar failures across different topics converge on the same signature. */
    val failureModeSignature: String? = null
)
