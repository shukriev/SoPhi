package dev.sophi.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class SessionSidecar(
    val parentSessionId: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null,
    val title: String? = null,
    val tip: String? = null
)

class FileSessionManager(private val sessionsDir: Path) : SessionManager {

    private companion object {
        // Generated ids are UUIDs; anything with path separators or dots could
        // resolve outside sessionsDir when the id arrives from an HTTP path.
        val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

        // JVM-wide: several managers can share one directory, and a second FileChannel.lock()
        // on a file this JVM already locks throws OverlappingFileLockException. Every read of a
        // session file takes it too: on macOS and Linux, closing any descriptor of a file drops
        // the process's lock on it, so nothing may open the file while a save holds the lock.
        val APPEND_LOCK = Any()
        const val CACHED_SESSIONS = 16
    }

    /** What each session's file held when this manager last read or wrote it: its size, and the latest version per entry id. */
    private class OnDisk(val size: Long, val entries: Map<String, SessionEntry>)
    private val onDisk = object : LinkedHashMap<String, OnDisk>(CACHED_SESSIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OnDisk>) = size > CACHED_SESSIONS
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

    // The .jsonl file is a log of entry versions: save() appends each entry that is new or changed
    // (compaction re-parents one), and load() keeps the last version per id. Nothing is rewritten.
    override fun save(session: AgentSession) {
        validateId(session.id)
        val file = sessionsDir.resolve("${session.id}.jsonl")
        // The tip goes first: a crash before the append leaves it naming an entry that isn't in
        // the file, and load() then falls back to the newest leaf, which is the entry this save adds.
        val current = readSidecar(session.id)
        writeSidecar(session.id, current.copy(
            parentSessionId = session.parentSessionId ?: current.parentSessionId,
            title = session.title ?: current.title,
            tip = session.tip?.id ?: current.tip
        ))
        synchronized(APPEND_LOCK) {
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val size = channel.size()
                    val known = onDisk[session.id]?.takeIf { it.size == size }?.entries ?: versionsOf(readAll(channel, size))
                    // ponytail: compares every entry with its cached version, in memory and without I/O; track a dirty set in AgentSession if 50k-entry sessions show it.
                    val changed = session.entries.filter { known[it.id] != it }
                    var written = size
                    if (changed.isNotEmpty()) {
                        val text = buildString {
                            if (size > 0 && !endsWithNewline(channel, size)) append('\n')
                            changed.forEach { append(json.encodeToString(it)).append('\n') }
                        }
                        val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                        while (buffer.hasRemaining()) written += channel.write(buffer, written) // at the end, which the lock holds still
                    }
                    onDisk[session.id] = OnDisk(written, known + changed.associateBy { it.id })
                }
            }
        }
    }

    private fun endsWithNewline(channel: FileChannel, size: Long): Boolean {
        val last = ByteBuffer.allocate(1)
        channel.read(last, size - 1)
        return last.get(0) == '\n'.code.toByte()
    }

    /** Reads through the locked channel itself: opening the file another way would drop the lock (see APPEND_LOCK). */
    private fun readAll(channel: FileChannel, size: Long): ByteArray {
        val buffer = ByteBuffer.allocate(Math.toIntExact(size))
        while (buffer.hasRemaining() && channel.read(buffer, buffer.position().toLong()) >= 0) Unit
        return buffer.array()
    }

    private fun readVersions(file: Path): Map<String, SessionEntry> =
        if (!file.exists()) emptyMap() else versionsOf(synchronized(APPEND_LOCK) { Files.readAllBytes(file) })

    /**
     * The latest version of each entry, in first-seen order. A line that doesn't decode (a crash cut
     * it short, maybe inside a multi-byte character, which the decoder replaces) is skipped.
     */
    private fun versionsOf(bytes: ByteArray): Map<String, SessionEntry> {
        val versions = LinkedHashMap<String, SessionEntry>()
        String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching { json.decodeFromString<SessionEntry>(line) }.getOrNull()?.let { versions[it.id] = it }
        }
        return versions
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
        val versions = readVersions(file)
        val sidecar = readSidecar(sessionId)
        // No usable tip (a crash before the append, or an old file): the newest leaf. Not the last
        // line, which after compaction is the summary pair, a root.
        val parents = versions.values.mapNotNullTo(HashSet()) { it.parentId }
        val tip = sidecar.tip?.takeIf { it in versions }
            ?: versions.values.filter { it.id !in parents }.sortedBy { it.timestamp }.lastOrNull()?.id // a tie goes to the later line
        return AgentSession(
            id = sessionId, title = sidecar.title, parentSessionId = sidecar.parentSessionId,
            initialEntries = versions.values.toList(), initialTipId = tip
        )
    }

    override fun list(): List<SessionMeta> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listDirectoryEntries("*.jsonl")
            .map { file ->
                val id = file.fileName.toString().removeSuffix(".jsonl")
                val sidecar = readSidecar(id)
                SessionMeta(
                    id = id,
                    entryCount = readVersions(file).size,
                    lastModifiedMillis = file.getLastModifiedTime().toMillis(),
                    parentSessionId = sidecar.parentSessionId,
                    title = sidecar.title
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

    fun readConfigSnapshot(sessionId: String): Pair<String?, String?> {
        validateId(sessionId)
        return readSidecar(sessionId).let { it.model to it.systemPrompt }
    }

    override fun rename(sessionId: String, title: String) {
        validateId(sessionId)
        writeSidecar(sessionId, readSidecar(sessionId).copy(title = title))
    }

    override fun delete(sessionId: String) {
        validateId(sessionId)
        synchronized(APPEND_LOCK) { onDisk.remove(sessionId) }
        Files.deleteIfExists(sessionsDir.resolve("$sessionId.jsonl"))
        Files.deleteIfExists(sessionsDir.resolve("$sessionId.meta.json"))
    }
}
