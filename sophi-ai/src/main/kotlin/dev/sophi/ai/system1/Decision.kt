package dev.sophi.ai.system1

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One tagged judgment call — a row of `~/.sophi/system1/decisions.jsonl` (ADR-042). Schema v1. */
@Serializable
data class Decision(
    val v: Int = 1,
    val kind: String = "decision",
    val id: String,
    val ts: String,
    val sessionId: String? = null,
    val judgment: String,
    val mode: System1Mode,
    /** "s1" or "s2": who produced the answer the caller got. */
    val served: String,
    val request: DecisionRequest,
    val s2: DecisionReply?,
    val s1: DecisionReply?
)

@Serializable
data class DecisionRequest(
    val systemPrompt: String?,
    val messages: List<DecisionMessage>,
    val maxTokens: Int,
    val temperature: Double
)

@Serializable
data class DecisionMessage(val role: String, val content: String)

@Serializable
data class DecisionReply(
    val model: String,
    val text: String?,
    val latencyMs: Long,
    val usage: DecisionUsage?,
    val error: String?
)

@Serializable
data class DecisionUsage(val inputTokens: Int, val outputTokens: Int)

// encodeDefaults: v and kind are defaults, and every row must carry them.
private val decisionJson = Json { encodeDefaults = true }

fun Decision.toJson(): String = decisionJson.encodeToString(Decision.serializer(), this)
