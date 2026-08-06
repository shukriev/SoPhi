package dev.sophi.core.session

data class SessionMeta(
    val id: String,
    val entryCount: Int,
    val lastModifiedMillis: Long,
    val parentSessionId: String? = null,
    val title: String? = null
)
