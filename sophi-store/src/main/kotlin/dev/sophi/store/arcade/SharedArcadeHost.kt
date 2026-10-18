package dev.sophi.store.arcade

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One [ArcadeHost] per server root per process, shared by everything that stores there (context,
 * memory, versioning — ADR-041 M6). A second `ArcadeHost` on the same root in one JVM would fight its
 * sibling for `host.lock`; leases avoid that. The host closes when the last lease does.
 */
object SharedArcadeHost {
    private class Entry(val host: ArcadeHost) { var leases = 0 }
    private val hosts = HashMap<Path, Entry>()

    // ponytail: one lock for all roots, held through election (≤ waitForHostMillis); per-root locks if that ever matters.
    @Synchronized
    fun acquire(root: Path, engineVersion: String = "sophi", waitForHostMillis: Long = 3_000): Lease {
        val key = root.toAbsolutePath().normalize()
        val entry = hosts.getOrPut(key) { Entry(ArcadeHost.open(key, engineVersion, waitForHostMillis)) }
        entry.leases++
        return Lease(entry.host, key)
    }

    @Synchronized
    private fun release(key: Path) {
        val e = hosts[key] ?: return
        if (--e.leases == 0) { hosts.remove(key); runCatching { e.host.close() } }
    }

    class Lease internal constructor(val host: ArcadeHost, private val key: Path) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release(key) }
    }
}
