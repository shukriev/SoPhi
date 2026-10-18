package dev.sophi.context

import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
import dev.sophi.store.arcade.SharedArcadeHost
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class IndexStatus(val ready: Boolean, val reason: String?, val role: String?, val projects: List<ProjectInfo>, val diskBytes: Long)

/**
 * The context index shared by every Sophi process (spec §4). Leases the process-wide [SharedArcadeHost]
 * (the `context` database next to memory and versioning) in the background so no turn waits on election; any process may *request* a scan, only the elected
 * host *runs* one, on a single writer thread — so TermStat/ContextMeta updates never race.
 * Every failure degrades to "not ready" for callers: the engine never breaks a turn.
 */
class ContextEngine(
    private val root: Path = defaultRoot(),
    private val git: GitProbe = GitProbe.system,
    private val scanInterval: Long = 30_000,
    private val drainEveryMillis: Long = 2_000,
    private val embedder: ChunkEmbedder? = ChunkEmbedder.fromKnobs(System.getenv())
) : AutoCloseable {

    companion object {
        const val SCHEMA_VERSION = 2
        const val NOT_READY = "index not ready — use grep/glob/read"
        const val DATABASE = "context"
        fun defaultRoot(): Path = ArcadeHost.defaultRoot()
        // ponytail: one writer per root per process — two engines on one root in one JVM share a host and would
        // both drain IndexRequests; a lock map is enough since that only happens in tests and settings rebuilds.
        private val writers = java.util.concurrent.ConcurrentHashMap<Path, Any>()
    }

    @Volatile private var lease: SharedArcadeHost.Lease? = null
    private val started = AtomicBoolean(false)
    private val writerLock: Any = writers.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }
    @Volatile private var closed = false
    @Volatile private var lastError = 0L
    private val opened = CountDownLatch(1)
    private var hostTermPrepared = false   // writer thread only
    private var embedPrepared = false      // writer thread only
    private var embedIdleUntil = 0L        // writer thread only; Long.MAX_VALUE = idle until a scan changes something
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sophi-context-writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    val ready: Boolean get() = lease != null && !closed

    /** Why the index failed to open; null while opening or ready. */
    @Volatile var unavailableReason: String? = null; private set

    fun start(): ContextEngine {
        check(started.compareAndSet(false, true)) { "ContextEngine already started" }
        Thread({
            try {
                val l = SharedArcadeHost.acquire(root, engineVersion = "sophi-context/$SCHEMA_VERSION")
                var registered = false
                try {
                    l.host.register(DATABASE, SCHEMA_VERSION); registered = true
                    l.host.withStore(DATABASE) { ContextSchema.ensure(it) }
                } catch (e: Exception) { if (registered) l.host.unregister(DATABASE); l.close(); throw e }
                synchronized(this) { lease = l }   // paired with release()
                if (closed) release()
            } catch (e: IncompatibleHostException) {
                unavailableReason = "another Sophi version is using the index — update this app or the CLI"
                System.err.println("context: ${e.message} — update this Sophi to use the shared index")
            } catch (e: Exception) {
                unavailableReason = "index unavailable (${e.message})"
                System.err.println("context: index unavailable (${e.message})")
            } finally { opened.countDown() }
        }, "sophi-context-open").apply { isDaemon = true }.start()
        writer.scheduleWithFixedDelay({ runCatching { drain() } }, drainEveryMillis, drainEveryMillis, TimeUnit.MILLISECONDS)
        return this
    }

    fun awaitReady(timeoutMillis: Long): Boolean = opened.await(timeoutMillis, TimeUnit.MILLISECONDS) && ready

    fun <T> withStoreOrNull(block: (ArcadeStore) -> T): T? {
        val h = lease?.host ?: return null
        return try { h.withStore(DATABASE, block) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); null
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastError > 60_000) { lastError = now; System.err.println("context: store call failed (${e.message})") }
            null
        }
    }

    fun register(path: Path, auto: Boolean): RegisterResult {
        val resolved = ProjectRegistry.resolveRoot(path, autoOnly = auto)
            ?: return RegisterResult.Refused("not a git repository and no AGENTS.md/CLAUDE.md/.sophi/instructions.md — run `sophi context add <dir>` to index it anyway")
        ProjectRegistry.refusal(resolved)?.let { return RegisterResult.Refused(it) }
        return withStoreOrNull { s ->
            val existing = projects(s)
            existing.firstOrNull { resolved.startsWith(Path.of(it.root)) }?.let { return@withStoreOrNull RegisterResult.AlreadyCovered(it) }
            s.deleteDocument(ContextSchema.REQUEST, "drop:${ProjectRegistry.projectId(resolved)}")
            val children = existing.filter { Path.of(it.root).startsWith(resolved) }
            children.forEach { c -> s.deleteDocument(ContextSchema.PROJECT, c.id); s.upsertDocument(ContextSchema.REQUEST, "drop:${c.id}", mapOf("projectId" to c.id, "kind" to "drop", "requestedAt" to System.currentTimeMillis())) }
            val id = ProjectRegistry.projectId(resolved)
            s.upsertDocument(ContextSchema.PROJECT, id, mapOf("root" to resolved.toString(), "name" to resolved.fileName.toString(), "status" to "active", "isGlobal" to false))
            requestScan(s, id)
            RegisterResult.Registered(projects(s).first { it.id == id }, children.map { it.id })
        } ?: RegisterResult.Refused(NOT_READY)
    }

    fun projects(): List<ProjectInfo> = projectsOrNull() ?: emptyList()

    /** null = not ready or the store call failed (unlike an empty list, which means nothing is registered). */
    fun projectsOrNull(): List<ProjectInfo>? = withStoreOrNull { projects(it) }

    fun projectContaining(path: Path): ProjectInfo? {
        val p = runCatching { path.toRealPath() }.getOrNull() ?: return null
        return projects().filter { p.startsWith(Path.of(it.root)) }.maxByOrNull { it.root.length }
    }

    fun remove(projectId: String) {
        withStoreOrNull { s ->
            s.deleteDocument(ContextSchema.PROJECT, projectId)
            s.upsertDocument(ContextSchema.REQUEST, "drop:$projectId", mapOf("projectId" to projectId, "kind" to "drop", "requestedAt" to System.currentTimeMillis()))
        }
    }

    /** false = not ready or unknown project id. */
    fun setGlobal(projectId: String, global: Boolean): Boolean = withStoreOrNull { s ->
        if (s.query("SELECT id FROM ${ContextSchema.PROJECT} WHERE id = ?", projectId).isEmpty()) false
        else { s.command("UPDATE ${ContextSchema.PROJECT} SET isGlobal = ? WHERE id = ?", global, projectId); true }
    } ?: false

    fun status(): IndexStatus {
        val projects = if (closed) null else projectsOrNull()
        return IndexStatus(ready = projects != null, reason = if (projects != null) null else unavailableReason ?: NOT_READY,
            role = lease?.host?.role?.let { if (it == Role.HOST) "host" else "client" }, projects = projects ?: emptyList(),
            diskBytes = dirSize(root.resolve("databases").resolve(DATABASE)))
    }

    /** Best effort: ArcadeDB creates/deletes files while we walk, so unreadable or vanished entries are skipped. */
    internal fun dirSize(p: Path): Long {
        var total = 0L
        try {
            Files.walkFileTree(p, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
            })
        } catch (e: Exception) { /* partial sum */ }
        return total
    }

    fun requestScan(projectId: String) { withStoreOrNull { requestScan(it, projectId) } }

    /** [vectors] = false keeps the BM25 scale (the hint's gate is calibrated on it). The query is embedded outside the store call. */
    fun search(query: String, projectIds: List<String>, limit: Int = 8, vectors: Boolean = true): List<SearchHit>? {
        val qv = if (vectors) embedder?.queryVector(query) else null
        return withStoreOrNull { s ->
            requestStaleScans(s, projectIds)
            Retriever(s, Weights(), qv?.let { v -> { _: String -> v } }).search(query, projectIds, limit)
        }
    }

    private fun requestStaleScans(s: ArcadeStore, projectIds: List<String>) {
        val now = System.currentTimeMillis()
        projects(s).filter { it.id in projectIds && (it.lastScanAt == null || now - it.lastScanAt > scanInterval) }
            .forEach { requestScan(s, it.id) }
    }

    fun findSymbol(name: String, projectIds: List<String>, limit: Int = 10): List<SymbolHit>? = withStoreOrNull { s ->
        requestStaleScans(s, projectIds)
        Graph(s).findSymbol(name, projectIds, limit)
    }

    fun related(projectId: String, path: String): RelatedFiles? = withStoreOrNull { Graph(it).related(projectId, path) }

    fun fileIndexed(projectId: String, relPath: String): Boolean = fileIndexedOrNull(projectId, relPath) ?: false

    fun fileIndexedOrNull(projectId: String, relPath: String): Boolean? =
        withStoreOrNull { it.query("SELECT id FROM ${ContextSchema.FILE} WHERE id = ?", ContextSchema.fileId(projectId, relPath)).isNotEmpty() }

    fun fileSummaries(projectId: String): List<Triple<String, String, String?>> = fileSummariesOrNull(projectId) ?: emptyList()

    fun fileSummariesOrNull(projectId: String): List<Triple<String, String, String?>>? =
        withStoreOrNull { s -> s.query("SELECT path, role, summary FROM ${ContextSchema.FILE} WHERE projectId = ?", projectId)
            .map { Triple(it["path"] as String, it["role"] as String, it["summary"] as String?) } }

    /** Summaries of just these files (a few id lookups, not the whole table); null = store failed. */
    fun summariesOrNull(projectId: String, paths: List<String>): Map<String, String?>? = withStoreOrNull { s ->
        paths.distinct().associateWith { p ->
            s.query("SELECT summary FROM ${ContextSchema.FILE} WHERE id = ?", ContextSchema.fileId(projectId, p)).firstOrNull()?.get("summary") as String?
        }
    }

    override fun close() {
        closed = true
        writer.shutdown()   // let an in-flight scan finish its file: Indexer polls `closed`
        runCatching { writer.awaitTermination(10, TimeUnit.SECONDS) }
        release()
    }

    /** Idempotent: the lease and its one registration are dropped once, whoever (close or the open thread) gets here first. */
    @Synchronized
    private fun release() {
        val l = lease ?: return
        lease = null
        runCatching { l.host.unregister(DATABASE) }
        l.close()
    }

    private fun requestScan(s: ArcadeStore, projectId: String) =
        s.upsertDocument(ContextSchema.REQUEST, projectId, mapOf("projectId" to projectId, "kind" to "scan", "requestedAt" to System.currentTimeMillis()))

    private fun projects(s: ArcadeStore): List<ProjectInfo> = s.documents(ContextSchema.PROJECT).map { r ->
        ProjectInfo(r["id"] as String, r["root"] as String, r["name"] as String, (r["status"] as String?) ?: "active",
            (r["isGlobal"] as Boolean?) ?: false, (r["lastScanAt"] as Number?)?.toLong(), (r["fileCount"] as Number?)?.toInt(),
            (r["partial"] as Boolean?) ?: false, (r["modules"] as List<*>?)?.map { it.toString() } ?: emptyList(),
            (r["scanned"] as Number?)?.toInt(), (r["scanTotal"] as Number?)?.toInt())
    }.sortedBy { it.root }

    /** Writer thread only. */
    private fun drain() {
        val h = lease?.host ?: return
        if (h.role != Role.HOST) { hostTermPrepared = false; embedPrepared = false; embedIdleUntil = 0; return }
        synchronized(writerLock) {
            if (closed || lease?.host !== h) return   // closed or released while waiting for the lock
            h.withStore(DATABASE) { s ->
            val indexer = Indexer(s, git, cancelled = { closed })
            if (!hostTermPrepared) { indexer.rebuildIfStale(SCHEMA_VERSION); hostTermPrepared = true }
            s.documents(ContextSchema.REQUEST).sortedBy { (it["requestedAt"] as Number?)?.toLong() ?: 0L }.forEach { req ->
                if (closed) return@withStore
                val id = req["id"] as String
                val projectId = req["projectId"] as String
                s.deleteDocument(ContextSchema.REQUEST, id)
                if (req["kind"] == "drop") indexer.dropProject(projectId)
                else indexer.scan(projectId).let { if (it.added + it.changed > 0 && embedIdleUntil == Long.MAX_VALUE) embedIdleUntil = 0 }
            }
            val e = embedder
            if (e != null && !closed && System.currentTimeMillis() >= embedIdleUntil) {
                try {
                    if (!embedPrepared) { e.prepare(s); embedPrepared = true }
                    if (e.embedPending(s, cancelled = { closed }) == 0) embedIdleUntil = Long.MAX_VALUE
                } catch (ex: InterruptedException) { Thread.currentThread().interrupt()
                } catch (ex: Exception) {
                    embedIdleUntil = System.currentTimeMillis() + 60_000   // backoff; lexical meanwhile
                    System.err.println("context: embedding paused for 60 s (${ex.message})")
                }
            }
        } }
    }
}
