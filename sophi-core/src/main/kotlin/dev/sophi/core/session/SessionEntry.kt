package dev.sophi.core.session

import kotlinx.serialization.Serializable

@Serializable
data class SessionEntry(
    val id: String,
    val parentId: String? = null,
    val role: EntryRole,
    val content: String,
    val timestamp: Long,
    val metadata: Map<String, String> = emptyMap()
)
