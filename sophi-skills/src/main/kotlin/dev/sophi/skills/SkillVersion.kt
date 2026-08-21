package dev.sophi.skills

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SkillVersion(
    val id: String = "skillver_" + UUID.randomUUID(),
    val ts: Long = System.currentTimeMillis(),
    val skillId: String,
    val project: Boolean,
    /** The full rendered file (frontmatter + body) at this point in time, not a diff. */
    val content: String,
    /** Set by a future capability phase when this version is on a revertible trial period. */
    val trial: Boolean = false
)
