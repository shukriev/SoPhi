package dev.sophi.schedule.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Proposal(
    val id: String = "prop_" + UUID.randomUUID(),
    val ts: Long = System.currentTimeMillis(),
    val sessionId: String,
    val title: String,
    val category: String,
    val rationale: String,
    val suggestedAction: String,
    val status: String = "pending",
    val reviewedAtMs: Long? = null,
    val reviewReason: String? = null
)
