package dev.sophi.skills

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class SkillInvocationStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(event: SkillInvocationEvent) {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(event)
        Files.write(path, (line + "\n").toByteArray(), CREATE, APPEND)
    }

    fun all(): List<SkillInvocationEvent> =
        if (!Files.exists(path)) emptyList()
        else Files.readAllLines(path).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<SkillInvocationEvent>(it) }.getOrNull() }
}
