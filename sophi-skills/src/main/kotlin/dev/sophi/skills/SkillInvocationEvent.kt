package dev.sophi.skills

import kotlinx.serialization.Serializable

@Serializable
data class SkillInvocationEvent(
    val ts: Long = System.currentTimeMillis(),
    val sessionId: String,
    val skillId: String
)
