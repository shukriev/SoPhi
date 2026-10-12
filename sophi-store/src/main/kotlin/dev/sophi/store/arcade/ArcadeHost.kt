package dev.sophi.store.arcade

import com.arcadedb.serializer.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

enum class Role { HOST, CLIENT }

/** The running host was built with a different store schema; attaching could corrupt it. */
class IncompatibleHostException(message: String) : IllegalStateException(message)

/**
 * One ArcadeDB store shared by every Sophi process on this machine (ADR-041). Whoever takes
 * `root/host.lock` first runs [LocalArcadeServer] and uses the database in-process; everyone else
 * reads `root/host.json` and goes through [RemoteArcadeStore]. When the host disappears, the
 * client's next [withStore] call re-runs the election and usually becomes the host itself.
 */
class ArcadeHost private constructor(
    private val root: Path,
    private val database: String,
    private val schemaVersion: Int,
    private val engineVersion: String,
    private val waitForHostMillis: Long
) : AutoCloseable {

    private val lockChannel: FileChannel =
        FileChannel.open(root.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private var lock: FileLock? = null
    private var server: LocalArcadeServer? = null
    @Volatile private var current: ArcadeStore? = null
    @Volatile var role: Role = Role.CLIENT
        private set

    companion object {
        private const val POLL_MILLIS = 50L

        fun open(
            root: Path, database: String, schemaVersion: Int, engineVersion: String,
            waitForHostMillis: Long = 3_000
        ): ArcadeHost {
            Files.createDirectories(root)
            return ArcadeHost(root, database, schemaVersion, engineVersion, waitForHostMillis).also {
                try { it.elect() } catch (e: Exception) { it.close(); throw e }
            }
        }
    }

    /**
     * Runs [block] against the current store. If it fails while this process is a client and the
     * host is gone, re-elects once and runs [block] again — so [block] may run twice and must be
     * safe to repeat (every write here is an upsert into a rebuildable cache).
     */
    fun <T> withStore(block: (ArcadeStore) -> T): T {
        val store = current ?: synchronized(this) { current ?: elect() }
        return try {
            block(store)
        } catch (e: Exception) {
            if (role != Role.CLIENT || hostAlive()) throw e
            block(synchronized(this) { elect() })
        }
    }

    @Synchronized
    private fun elect(): ArcadeStore {
        current?.let { runCatching { it.close() } }
        current = null
        val acquired = try { lockChannel.tryLock() } catch (e: OverlappingFileLockException) { null }
        val store = if (acquired != null) becomeHost(acquired) else becomeClient()
        current = store
        return store
    }

    private fun becomeHost(acquired: FileLock): ArcadeStore {
        lock = acquired
        val srv = LocalArcadeServer.start(root)
        server = srv
        val store = EmbeddedArcadeStore(srv.database(database))
        writeHostJson(srv.port)
        role = Role.HOST
        return store
    }

    private fun becomeClient(): ArcadeStore {
        val info = awaitHostJson()
        val theirs = info.getInt("schemaVersion")
        if (theirs != schemaVersion) {
            throw IncompatibleHostException(
                "store at $root is served by ${info.getString("engineVersion")} (schema $theirs); this build uses schema $schemaVersion"
            )
        }
        val password = LocalArcadeServer.readSecret(root)
            ?: throw IllegalStateException("host is running but $root/secret is missing")
        role = Role.CLIENT
        return RemoteArcadeStore.connect(info.getInt("port"), database, password)
    }

    /** Waits for a host.json written by a live process — a stale file from a crashed host is ignored. */
    private fun awaitHostJson(): JSONObject {
        val deadline = System.nanoTime() + waitForHostMillis * 1_000_000
        while (true) {
            readHostJson()?.takeIf { pidAlive(it.getLong("pid")) }?.let { return it }
            if (System.nanoTime() > deadline) throw IllegalStateException("no live host for $root after ${waitForHostMillis} ms")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun hostAlive(): Boolean = readHostJson()?.let { pidAlive(it.getLong("pid")) } ?: false

    private fun pidAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun readHostJson(): JSONObject? =
        root.resolve("host.json").takeIf { Files.exists(it) }
            ?.let { runCatching { JSONObject(Files.readString(it)) }.getOrNull() }

    private fun writeHostJson(port: Int) {
        val json = JSONObject()
            .put("pid", ProcessHandle.current().pid())
            .put("port", port)
            .put("engineVersion", engineVersion)
            .put("schemaVersion", schemaVersion)
        val tmp = Files.createTempFile(root, "host", ".json")
        restrictToOwner(tmp)
        Files.writeString(tmp, json.toString())
        Files.move(tmp, root.resolve("host.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun close() {
        if (role == Role.HOST) {
            // The server owns the embedded database instance; stopping it closes the database.
            Files.deleteIfExists(root.resolve("host.json"))
            server?.close()
        } else {
            current?.let { runCatching { it.close() } }
        }
        current = null
        server = null
        lock?.let { runCatching { it.release() } }
        lock = null
        lockChannel.close()
    }
}
