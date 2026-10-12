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
    private var connectedPort: Int = -1
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
     * host it was talking to is gone, re-elects once and runs [block] again — so [block] may run
     * twice and must be safe to repeat (every write here is an upsert into a rebuildable cache).
     */
    fun <T> withStore(block: (ArcadeStore) -> T): T {
        val store = current ?: synchronized(this) { current ?: elect() }
        try {
            return block(store)
        } catch (e: Exception) {
            val next = synchronized(this) {
                val now = current
                if (now != null && now !== store) now // someone already re-elected
                else try {
                    val acquired = tryLockOrNull()
                    if (acquired != null) {
                        current?.let { runCatching { it.close() } }
                        current = null
                        becomeHost(acquired).also { current = it }
                    } else {
                        if (role == Role.HOST) throw e // a host's own error is genuine
                        val info = liveHostJson()
                        if (info != null && runCatching { info.getInt("port") }.getOrNull() == connectedPort) throw e
                        elect()
                    }
                } catch (e2: Throwable) {
                    if (e2 !== e) e2.addSuppressed(e)
                    throw e2
                }
            }
            try {
                return block(next)
            } catch (e2: Exception) {
                e2.addSuppressed(e)
                throw e2
            }
        }
    }

    private fun tryLockOrNull(): FileLock? = try { lockChannel.tryLock() } catch (e: OverlappingFileLockException) { null }

    /** The lock is the source of truth: take it if free, else attach to the live host.json, until the deadline. */
    @Synchronized
    private fun elect(): ArcadeStore {
        current?.let { runCatching { it.close() } }
        current = null
        val deadline = System.nanoTime() + waitForHostMillis * 1_000_000
        var last: Exception? = null
        while (true) {
            val acquired = tryLockOrNull()
            if (acquired != null) return becomeHost(acquired).also { current = it }
            liveHostJson()?.let {
                try { return becomeClient(it).also { s -> current = s } }
                catch (e: IncompatibleHostException) { throw e }
                catch (e: Exception) { last = e } // host may have just died; keep polling
            }
            if (System.nanoTime() > deadline) throw IllegalStateException("no live host for $root after $waitForHostMillis ms", last)
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun becomeHost(acquired: FileLock): ArcadeStore {
        lock = acquired
        try {
            server = LocalArcadeServer.start(root)
            val srv = server!!
            val store = EmbeddedArcadeStore(srv.database(database))
            writeHostJson(srv.port)
            role = Role.HOST
            connectedPort = srv.port
            return store
        } catch (e: Throwable) {
            runCatching { server?.close() }
            runCatching { acquired.release() }
            server = null; lock = null; current = null; role = Role.CLIENT
            throw e
        }
    }

    private fun becomeClient(info: JSONObject): ArcadeStore {
        val theirs = info.getInt("schemaVersion")
        if (theirs != schemaVersion) {
            throw IncompatibleHostException(
                "store at $root is served by ${runCatching { info.getString("engineVersion") }.getOrDefault("?")} (schema $theirs); this build uses schema $schemaVersion"
            )
        }
        val password = LocalArcadeServer.readSecret(root)
            ?: throw IllegalStateException("host is running but $root/secret is missing")
        val port = info.getInt("port")
        val store = RemoteArcadeStore.connect(port, database, password)
        role = Role.CLIENT
        connectedPort = port
        return store
    }

    /** host.json if readable, complete and written by a live process; else null. */
    private fun liveHostJson(): JSONObject? =
        readHostJson()?.takeIf {
            runCatching { it.getInt("port"); it.getInt("schemaVersion"); pidAlive(it.getLong("pid")) }.getOrDefault(false)
        }

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
        try {
            restrictToOwner(tmp)
            Files.writeString(tmp, json.toString())
            Files.move(tmp, root.resolve("host.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    @Synchronized
    override fun close() {
        try {
            if (role == Role.HOST) {
                // The server owns the embedded database instance; stopping it closes the database.
                try { Files.deleteIfExists(root.resolve("host.json")) } finally { server?.close() }
            } else {
                current?.let { runCatching { it.close() } }
            }
        } finally {
            current = null
            server = null
            lock?.let { runCatching { it.release() } }
            lock = null
            lockChannel.close()
        }
    }
}
