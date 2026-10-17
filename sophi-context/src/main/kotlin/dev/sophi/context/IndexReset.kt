package dev.sophi.context

import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
import dev.sophi.store.arcade.SharedArcadeHost
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

sealed interface ResetResult {
    data object Done : ResetResult
    data object InUse : ResetResult
    data object OtherVersion : ResetResult
}

/**
 * Moves the context database aside — only when no other process hosts it and no engine in this process has it open;
 * other databases on the same server are untouched. Leaves secret/host.json/the lock alone.
 * Only engines in this process are seen: a context client in another process is not detected, and recovers on
 * its next call (its stale store is evicted and reconnects to the recreated database).
 */
class IndexReset(private val root: Path) {
    fun run(): ResetResult {
        val lease = try { SharedArcadeHost.acquire(root, "reset", waitForHostMillis = 500) } catch (e: Exception) { null }
        try {
            if (lease != null) {
                val h = lease.host
                if (h.role == Role.CLIENT) {
                    // Another process hosts: never reset under it. Tell a version mismatch apart for the message.
                    try { h.register(ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION); h.unregister(ContextEngine.DATABASE) }
                    catch (e: IncompatibleHostException) { return ResetResult.OtherVersion }
                    return ResetResult.InUse
                }
                if (h.users(ContextEngine.DATABASE) > 0) return ResetResult.InUse   // an engine in this process still has it
                h.detach(ContextEngine.DATABASE)   // memory and versioning on the same server keep running
            } else if (lockHeld()) return ResetResult.InUse   // a live host we couldn't attach to, not a corrupt database
            val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
            // outside databases/: the server would try to load a moved-aside corrupt copy
            if (Files.exists(db)) Files.move(db, root.resolve("${ContextEngine.DATABASE}.corrupt-${System.currentTimeMillis()}"))
            return ResetResult.Done
        } finally { lease?.close() }
    }

    private fun lockHeld(): Boolean {
        Files.createDirectories(root)
        return try {
            FileChannel.open(root.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                val l = ch.tryLock(); l?.release(); l == null
            }
        } catch (e: OverlappingFileLockException) { true }
    }
}
