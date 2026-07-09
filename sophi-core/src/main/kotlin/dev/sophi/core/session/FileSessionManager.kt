package dev.sophi.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class SessionSidecar(
    val parentSessionId: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null
)

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
        atomicWrite(file, session.entries.joinToString("\n") { json.encodeToString(it) })

        if (session.parentSessionId != null) {
            writeSidecar(session.id, readSidecar(session.id).copy(parentSessionId = session.parentSessionId))
        }
    }

    // Write-in-place would corrupt the file if the process dies mid-write;
    // stage to a temp file in the same directory and atomically rename over.
    private fun atomicWrite(target: Path, content: String) {
        val tmp = Files.createTempFile(sessionsDir, ".${target.fileName}", ".tmp")
        try {
            tmp.writeText(content)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun load(sessionId: String): AgentSession {
        validateId(sessionId)
        val file = sessionsDir.resolve("$sessionId.jsonl")
        require(file.exists()) { "Session not found: $sessionId" }
        val entries = file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<SessionEntry>(it) }
        return AgentSession(id = sessionId, parentSessionId = readSidecar(sessionId).parentSessionId, initialEntries = entries)
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
                    parentSessionId = readSidecar(id).parentSessionId
                )
            }
            .sortedBy { it.id }
    }

    private fun readSidecar(sessionId: String): SessionSidecar {
        val sidecar = sessionsDir.resolve("$sessionId.meta.json")
        if (!sidecar.exists()) return SessionSidecar()
        return runCatching { json.decodeFromString<SessionSidecar>(sidecar.readText()) }
            .getOrDefault(SessionSidecar())
    }

    private fun writeSidecar(sessionId: String, sidecar: SessionSidecar) {
        if (sidecar == SessionSidecar()) return
        atomicWrite(sessionsDir.resolve("$sessionId.meta.json"), json.encodeToString(sidecar))
    }

    override fun saveConfigSnapshot(sessionId: String, model: String, systemPrompt: String?) {
        validateId(sessionId)
        writeSidecar(sessionId, readSidecar(sessionId).copy(model = model, systemPrompt = systemPrompt))
    }

    fun readConfigSnapshot(sessionId: String): Pair<String?, String?> =
        readSidecar(sessionId).let { it.model to it.systemPrompt }
}
