package dev.sophi.context

import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
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

/** Moves the context database aside — only when no Sophi process is hosting it. Leaves secret/host.json/the lock alone. */
class IndexReset(private val root: Path) {
    fun run(): ResetResult {
        val probe = try {
            ArcadeHost.open(root, ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION, "reset", waitForHostMillis = 500)
        } catch (e: IncompatibleHostException) {
            return ResetResult.OtherVersion
        } catch (e: Exception) { null }
        val isHost = try { probe?.role == Role.HOST } finally { probe?.close() }
        // A failed open is either a corrupted database (nobody hosting) or a live host we couldn't attach to:
        // only the lock tells them apart.
        if (!isHost && (probe != null || lockHeld())) return ResetResult.InUse
        val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
        // outside databases/: the server would try to load a moved-aside corrupt copy
        if (Files.exists(db)) Files.move(db, root.resolve("${ContextEngine.DATABASE}.corrupt-${System.currentTimeMillis()}"))
        return ResetResult.Done
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
