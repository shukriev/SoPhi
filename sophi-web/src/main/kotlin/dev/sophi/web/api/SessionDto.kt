package dev.sophi.web.api

data class SessionDto(
    val id: String,
    val entryCount: Int,
    val lastModifiedMillis: Long
)
