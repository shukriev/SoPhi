package dev.sophi.core.audit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Appends one JSON line per entry. The file lock makes concurrent processes (CLI and companion)
 * write whole lines. A FileChannel lock is held per JVM and throws OverlappingFileLockException
 * between threads (or between two sinks on one path), so the in-process part is a shared monitor.
 */
class JsonlAuditSink(
    private val path: Path = Path.of(System.getProperty("user.home"), ".sophi", "audit.jsonl"),
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) : AuditSink {
    private val warned = mutableSetOf<String>()

    override fun record(entry: AuditEntry): Unit = synchronized(LOCK) {
        try {
            path.parent?.let { Files.createDirectories(it) }
            val bytes = (Json.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8)
            FileChannel.open(path, CREATE, WRITE, APPEND).use { ch ->
                ch.lock().use { ch.write(ByteBuffer.wrap(bytes)) }
            }
        } catch (e: Exception) {
            val msg = "audit log write failed (${path}): ${e.message ?: e::class.simpleName}"
            if (warned.add(msg)) onWarning(msg)
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
