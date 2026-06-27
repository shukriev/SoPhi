package dev.sophi.core.session

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class FileSessionManager(private val sessionsDir: Path) : SessionManager {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        sessionsDir.createDirectories()
    }

    override fun create(title: String?): AgentSession =
        AgentSession(id = UUID.randomUUID().toString(), title = title)

    override fun save(session: AgentSession) {
        val file = sessionsDir.resolve("${session.id}.jsonl")
        file.writeText(session.entries.joinToString("\n") { json.encodeToString(it) })
    }

    override fun load(sessionId: String): AgentSession {
        val file = sessionsDir.resolve("$sessionId.jsonl")
        require(file.exists()) { "Session not found: $sessionId" }
        val entries = file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<SessionEntry>(it) }
        return AgentSession(id = sessionId, initialEntries = entries)
    }

    override fun list(): List<SessionMeta> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listDirectoryEntries("*.jsonl")
            .map { file ->
                val lines = file.readLines().filter { it.isNotBlank() }
                SessionMeta(
                    id = file.fileName.toString().removeSuffix(".jsonl"),
                    entryCount = lines.size,
                    lastModifiedMillis = file.getLastModifiedTime().toMillis()
                )
            }
            .sortedBy { it.id }
    }
}
