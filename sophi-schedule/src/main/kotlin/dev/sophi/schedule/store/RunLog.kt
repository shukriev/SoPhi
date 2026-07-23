package dev.sophi.schedule.store

import dev.sophi.schedule.model.RunRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class RunLog(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun append(record: RunRecord) {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(record).replace("\n", " ")
        Files.write(path, (line + "\n").toByteArray(), CREATE, APPEND)
    }

    fun readAll(): List<RunRecord> =
        if (!Files.exists(path)) emptyList()
        else Files.readAllLines(path).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<RunRecord>(it) }.getOrNull() }

    fun forTask(taskId: String): List<RunRecord> = readAll().filter { it.taskId == taskId }

    fun tail(n: Int): List<RunRecord> = readAll().takeLast(n)
}
