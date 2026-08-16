package dev.sophi.companion

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class NotificationStore(private val path: Path, private val maxRecords: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<NotificationRecord> = synchronized(lock) { readAll() }

    fun add(record: NotificationRecord): NotificationRecord = synchronized(lock) {
        writeAll((readAll() + record).takeLast(maxRecords))
        record
    }

    fun markAllRead() = synchronized(lock) {
        writeAll(readAll().map { it.copy(read = true) })
    }

    private fun readAll(): List<NotificationRecord> {
        if (!Files.exists(path)) return emptyList()
        val text = Files.readString(path)
        if (text.isBlank()) return emptyList()
        return json.decodeFromString<List<NotificationRecord>>(text)
    }

    private fun writeAll(records: List<NotificationRecord>) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString<List<NotificationRecord>>(records))
    }
}
