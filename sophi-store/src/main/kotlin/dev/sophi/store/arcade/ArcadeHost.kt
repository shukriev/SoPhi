package dev.sophi.store.arcade

import com.arcadedb.remote.RemoteServer
import com.arcadedb.serializer.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

enum class Role { HOST, CLIENT }

/** The running host was built with a different schema for this database; attaching could corrupt it. */
class IncompatibleHostException(message: String) : IllegalStateException(message)

/**
 * The ArcadeDB server shared by every Sophi process on this machine (ADR-041). Whoever takes
 * `root/host.lock` first runs [LocalArcadeServer] and opens databases in-process; everyone else reads
 * `root/host.json` and goes through [RemoteArcadeStore]. One server serves several databases: each
 * user [register]s the database and schema version it was built for, and a mismatch refuses only that
 * database. When the host disappears, the next failing [withStore] call re-runs the election and
 * usually becomes the host itself.
 */
class ArcadeHost private constructor(
    private val root: Path,
    private val engineVersion: String,
    private val waitForHostMillis: Long
) : AutoCloseable {

    private val lockChannel: FileChannel =
        FileChannel.open(root.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private var lock: FileLock? = null
    private var server: LocalArcadeServer? = null
    private val stores = ConcurrentHashMap<String, ArcadeStore>()
    private val schemas = ConcurrentHashMap<String, Int>()
    private val users = ConcurrentHashMap<String, Int>()
    @Volatile private var hostInfo: JSONObject? = null   // client only: the host.json it attached to
    @Volatile private var elected = false
    @Volatile private var generation = 0
    private var connectedPort: Int = -1
    private var defaultDatabase: String? = null
    @Volatile var role: Role = Role.CLIENT
        private set

    companion object {
        private const val POLL_MILLIS = 50L
        internal const val SCHEMA_TYPE = "SophiSchema"
        private const val SCHEMA_ID = "schema"
        private val DATABASE_NAME = Regex("[A-Za-z0-9_-]+")
        private const val PRE_M6_SENTINEL = -1

        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".sophi", "db")

        fun open(root: Path, engineVersion: String, waitForHostMillis: Long = 3_000): ArcadeHost {
            Files.createDirectories(root)
            return ArcadeHost(root, engineVersion, waitForHostMillis).also {
                try { it.elect() } catch (e: Exception) { it.close(); throw e }
            }
        }

        /** The one-database form (M0): [open] then [register]; [withStore] without a name uses [database]. */
        fun open(root: Path, database: String, schemaVersion: Int, engineVersion: String, waitForHostMillis: Long = 3_000): ArcadeHost =
            open(root, engineVersion, waitForHostMillis).also {
                try { it.register(database, schemaVersion); it.defaultDatabase = database } catch (e: Exception) { it.close(); throw e }
            }
    }

    /**
     * Declares that this process uses [database] at [schemaVersion]. Throws [IncompatibleHostException]
     * when the live host serves it at another version; other databases are unaffected. Each call counts
     * one user until [unregister].
     */
    @Synchronized
    fun register(database: String, schemaVersion: Int) {
        require(DATABASE_NAME.matches(database)) { "database name must match [A-Za-z0-9_-]+: '$database'" }
        schemas[database]?.let { require(it == schemaVersion) { "$database already registered at schema $it" } }
        // The host may have registered this database after we attached: check the current host.json first.
        if (role == Role.CLIENT) (liveHostJson() ?: hostInfo)?.let { checkSchema(database, schemaVersion, it) }
        val first = schemas.put(database, schemaVersion) == null
        users.merge(database, 1, Int::plus)
        if (first && role == Role.HOST) writeHostJson(connectedPort)
    }

    @Synchronized
    fun unregister(database: String) { users.computeIfPresent(database) { _, n -> (n - 1).takeIf { it > 0 } } }

    /** Registrations in this process currently using [database]. */
    fun users(database: String): Int = users[database] ?: 0

    fun <T> withStore(block: (ArcadeStore) -> T): T =
        withStore(checkNotNull(defaultDatabase) { "no default database — use withStore(database, block)" }, block)

    /**
     * Runs [block] against [database]. If it fails while this process is a client and the host it was
     * talking to is gone, re-elects once and runs [block] again — so [block] may run twice and must be
     * idempotent (insertVertex and raw commands are not upserts).
     */
    fun <T> withStore(database: String, block: (ArcadeStore) -> T): T {
        val gen = generation
        try {
            // Connecting is lazy, so a dead host can fail here too: inside the try so it fails over.
            return block(storeFor(database))
        } catch (e: Exception) {
            val next = synchronized(this) {
                if (generation != gen) storeFor(database) // someone already re-elected
                else try {
                    val acquired = tryLockOrNull()
                    if (acquired != null) {
                        closeStores()
                        becomeHost(acquired)
                    } else {
                        if (role == Role.HOST) throw e // a host's own error is genuine
                        val info = liveHostJson()
                        if (info != null && runCatching { info.getInt("port") }.getOrNull() == connectedPort) {
                            // The host is alive: drop our connection so the next call reconnects (e.g. after a detach).
                            stores.remove(database)?.let { runCatching { it.close() } }
                            throw e
                        }
                        elect()
                    }
                    storeFor(database)
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

    /** Host only: forgets [database] here and closes it on the server so its directory can be moved; the next use recreates it. */
    @Synchronized
    fun detach(database: String) {
        check(role == Role.HOST) { "only the host can detach $database" }
        stores.remove(database)
        server?.closeDatabase(database)
    }

    private fun storeFor(database: String): ArcadeStore = stores[database] ?: synchronized(this) {
        stores[database] ?: run {
            val version = schemas[database] ?: throw IllegalStateException("$database is not registered with this host")
            if (!elected) elect()
            val store = if (role == Role.HOST) EmbeddedArcadeStore(server!!.database(database))
                else hostInfo!!.let { checkSchema(database, version, it); connectRemote(it.getInt("port"), database) }
            try { checkMarker(database, version, store) }
            catch (e: Throwable) { if (role == Role.CLIENT) runCatching { store.close() }; throw e }
            store.also { stores[database] = it }
        }
    }

    /**
     * The schema version stored in the database itself, so a mismatch is caught whoever registered first
     * (host.json only knows what the host registered). Absent → ours is written; newer → refused; older →
     * only the host may upgrade it, a client is refused.
     */
    private fun checkMarker(database: String, mine: Int, store: ArcadeStore) {
        store.ensureSchema(documentTypes = listOf(SCHEMA_TYPE))
        val stored = store.documents(SCHEMA_TYPE).firstOrNull()?.get("version")?.let { (it as Number).toInt() }
        // ponytail: two first users can both see "absent" and the last write wins; a CAS would close that gap.
        when {
            stored == mine -> {}
            stored == null || (stored < mine && role == Role.HOST) ->
                store.upsertDocument(SCHEMA_TYPE, SCHEMA_ID, mapOf("version" to mine))
            else -> throw IncompatibleHostException(
                "$database at $root holds schema $stored; this build uses schema $mine" +
                    if (stored < mine) " and only the host may upgrade it" else ""
            )
        }
    }

    private fun checkSchema(database: String, mine: Int, info: JSONObject) {
        // M0–M5 hosts wrote one schemaVersion and no per-database map: it is checked against every database.
        val theirs: Int? = if (info.has("schemas")) info.getJSONObject("schemas").let { if (it.has(database)) it.getInt(database) else null }
            else info.getInt("schemaVersion")
        if (theirs != null && theirs != mine) throw IncompatibleHostException(
            "$database at $root is served by ${runCatching { info.getString("engineVersion") }.getOrDefault("?")} (schema $theirs); this build uses schema $mine"
        )
    }

    private fun connectRemote(port: Int, database: String): ArcadeStore {
        val password = secret()
        val remote = RemoteServer("127.0.0.1", port, LocalArcadeServer.USER, password)
        // `exists` lists only loaded databases (load-at-startup is off): one still on disk is opened, not created.
        // Another process may create it between exists and create; losing that race is fine.
        if (!remote.exists(database)) {
            if (Files.isDirectory(root.resolve("databases").resolve(database))) openOnServer(port, database, password)
            else runCatching { remote.create(database) }.onFailure { if (!remote.exists(database)) throw it }
        }
        return RemoteArcadeStore.connect(port, database, password)
    }

    /** RemoteServer has no "open database"; the HTTP API never loads one from disk by itself. */
    private fun openOnServer(port: Int, database: String, password: String) {
        val auth = Base64.getEncoder().encodeToString("${LocalArcadeServer.USER}:$password".toByteArray())
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/server"))
            .header("Authorization", "Basic $auth").header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("command", "open database $database").toString()))
            .build()
        // Runs under this object's monitor: bounded so a stuck host can never hang every caller.
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build()
            .use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
        check(response.statusCode() == 200) { "host could not open $database: ${response.statusCode()} ${response.body()}" }
    }

    private fun secret(): String =
        LocalArcadeServer.readSecret(root) ?: throw IllegalStateException("host is running but $root/secret is missing")

    private fun tryLockOrNull(): FileLock? = try { lockChannel.tryLock() } catch (e: OverlappingFileLockException) { null }

    /** The lock is the source of truth: take it if free, else attach to the live host.json, until the deadline. */
    @Synchronized
    private fun elect() {
        closeStores()
        elected = false
        val deadline = System.nanoTime() + waitForHostMillis * 1_000_000
        var last: Exception? = null
        while (true) {
            tryLockOrNull()?.let { becomeHost(it); return }
            liveHostJson()?.let {
                try { becomeClient(it); return } catch (e: Exception) { last = e } // host may have just died; keep polling
            }
            if (System.nanoTime() > deadline) throw IllegalStateException("no live host for $root after $waitForHostMillis ms", last)
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun becomeHost(acquired: FileLock) {
        lock = acquired
        try {
            val srv = LocalArcadeServer.start(root)
            server = srv
            connectedPort = srv.port
            role = Role.HOST
            hostInfo = null
            writeHostJson(srv.port)
            elected = true
            generation++
        } catch (e: Throwable) {
            runCatching { server?.close() }
            runCatching { acquired.release() }
            server = null; lock = null; role = Role.CLIENT; elected = false
            throw e
        }
    }

    private fun becomeClient(info: JSONObject) {
        val port = info.getInt("port")
        RemoteServer("127.0.0.1", port, LocalArcadeServer.USER, secret()).databases() // throws unless it really serves
        hostInfo = info
        connectedPort = port
        role = Role.CLIENT
        elected = true
        generation++
    }

    /** host.json if readable, complete and written by a live process; else null. */
    private fun liveHostJson(): JSONObject? =
        readHostJson()?.takeIf { runCatching { it.getInt("port"); pidAlive(it.getLong("pid")) }.getOrDefault(false) }

    private fun pidAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun readHostJson(): JSONObject? =
        root.resolve("host.json").takeIf { Files.exists(it) }
            ?.let { runCatching { JSONObject(Files.readString(it)) }.getOrNull() }

    private fun writeHostJson(port: Int) {
        val json = JSONObject()
            .put("pid", ProcessHandle.current().pid())
            .put("port", port)
            .put("engineVersion", engineVersion)
            // M0–M5 clients compare this single int: a sentinel no schema uses makes every one of them refuse.
            .put("schemaVersion", PRE_M6_SENTINEL)
            .put("schemas", JSONObject().also { j -> schemas.forEach { (db, v) -> j.put(db, v) } })
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

    /** A client's remote stores are closed; a host's belong to its server, which closes them on stop. */
    private fun closeStores() {
        if (role == Role.CLIENT) stores.values.forEach { runCatching { it.close() } }
        stores.clear()
    }

    @Synchronized
    override fun close() {
        try {
            if (role == Role.HOST) {
                try { Files.deleteIfExists(root.resolve("host.json")) } finally { server?.close() }
            } else {
                closeStores()
            }
        } finally {
            stores.clear()
            server = null
            elected = false
            lock?.let { runCatching { it.release() } }
            lock = null
            lockChannel.close()
        }
    }
}
