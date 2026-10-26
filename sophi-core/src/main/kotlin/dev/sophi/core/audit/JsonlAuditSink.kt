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

// Defaults on so every line carries "v" and "tenant" (null) and a reader can rely on the shape.
private val AUDIT_JSON = Json { encodeDefaults = true; explicitNulls = true }

/**
 * Appends one JSON line per entry. The file lock makes concurrent processes (CLI and companion)
 * write whole lines. A FileChannel lock is held per JVM and throws OverlappingFileLockException
 * between threads (or between two sinks on one path), so the in-process part is a shared monitor.
 * "Warn once" is per distinct message.
 */
class JsonlAuditSink(
    private val path: Path = Path.of(System.getProperty("user.home"), ".sophi", "audit.jsonl"),
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) : AuditSink {
    private val warned = mutableSetOf<String>()

    override fun record(entry: AuditEntry): Unit = synchronized(LOCK) {
        try {
            path.parent?.let { Files.createDirectories(it) }
            val bytes = (AUDIT_JSON.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8)
            FileChannel.open(path, CREATE, WRITE, APPEND).use { ch ->
                ch.lock().use {
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                }
            }
        } catch (e: Exception) {
            val msg = "audit log write failed (${path}): ${e.message ?: e::class.simpleName}"
            if (warned.add(msg)) runCatching { onWarning(msg) } // a throwing warning hook must not fail the tool call
        }
    }

    // ponytail: one JVM-wide lock, so writers to different files serialise too; per-path locks if that ever shows up.
    private companion object {
        val LOCK = Any()
    }
}
