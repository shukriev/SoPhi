package dev.sophi.companion

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class NotificationKind { Schedule, Confirmation, Memory, Mcp }

@Serializable
data class NotificationRecord(
    val id: String = "notif_" + UUID.randomUUID(),
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val read: Boolean = false
)
