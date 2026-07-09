package dev.sophi.core.session

import kotlinx.serialization.Serializable
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
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class SessionSidecar(val parentSessionId: String? = null)

class FileSessionManager(private val sessionsDir: Path) : SessionManager {

    private companion object {
        // Generated ids are UUIDs; anything with path separators or dots could
        // resolve outside sessionsDir when the id arrives from an HTTP path.
        val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun validateId(sessionId: String) {
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Invalid session id: $sessionId" }
    }

    init {
        sessionsDir.createDirectories()
    }

    override fun create(title: String?, parentSessionId: String?): AgentSession =
        AgentSession(id = UUID.randomUUID().toString(), title = title, parentSessionId = parentSessionId)

    override fun save(session: AgentSession) {
        validateId(session.id)
        val file = sessionsDir.resolve("${session.id}.jsonl")
        file.writeText(session.entries.joinToString("\n") { json.encodeToString(it) })

        if (session.parentSessionId != null) {
            val sidecar = sessionsDir.resolve("${session.id}.meta.json")
            sidecar.writeText(json.encodeToString(SessionSidecar(session.parentSessionId)))
        }
    }

    override fun load(sessionId: String): AgentSession {
        validateId(sessionId)
        val file = sessionsDir.resolve("$sessionId.jsonl")
        require(file.exists()) { "Session not found: $sessionId" }
        val entries = file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<SessionEntry>(it) }
        return AgentSession(id = sessionId, parentSessionId = readParentSessionId(sessionId), initialEntries = entries)
    }

    override fun list(): List<SessionMeta> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listDirectoryEntries("*.jsonl")
            .map { file ->
                val lines = file.readLines().filter { it.isNotBlank() }
                val id = file.fileName.toString().removeSuffix(".jsonl")
                SessionMeta(
                    id = id,
                    entryCount = lines.size,
                    lastModifiedMillis = file.getLastModifiedTime().toMillis(),
                    parentSessionId = readParentSessionId(id)
                )
            }
            .sortedBy { it.id }
    }

    private fun readParentSessionId(sessionId: String): String? {
        val sidecar = sessionsDir.resolve("$sessionId.meta.json")
        if (!sidecar.exists()) return null
        return runCatching { json.decodeFromString<SessionSidecar>(sidecar.readText()).parentSessionId }
            .getOrNull()
    }
}
