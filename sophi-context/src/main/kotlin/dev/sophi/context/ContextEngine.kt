package dev.sophi.context

import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The context index shared by every Sophi process (spec §4). Opens the M0 [ArcadeHost] in the
 * background so no turn waits on election; any process may *request* a scan, only the elected
 * host *runs* one, on a single writer thread — so TermStat/ContextMeta updates never race.
 * Every failure degrades to "not ready" for callers: the engine never breaks a turn.
 */
class ContextEngine(
    private val root: Path = defaultRoot(),
    private val git: GitProbe = GitProbe.system,
    private val scanInterval: Long = 30_000,
    private val drainEveryMillis: Long = 2_000
) : AutoCloseable {

    companion object {
        const val SCHEMA_VERSION = 1
        const val NOT_READY = "index not ready — use grep/glob/read"
        const val DATABASE = "context"
        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".sophi", "db")
    }

    @Volatile private var host: ArcadeHost? = null
    @Volatile private var closed = false
    @Volatile private var lastError = 0L
    private val opened = CountDownLatch(1)
    private var hostTermPrepared = false   // writer thread only
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sophi-context-writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    val ready: Boolean get() = host != null && !closed

    fun start(): ContextEngine {
        Thread({
            try {
                val h = ArcadeHost.open(root, DATABASE, SCHEMA_VERSION, engineVersion = "sophi-context/$SCHEMA_VERSION")
                h.withStore { ContextSchema.ensure(it) }
                if (closed) h.close() else host = h
            } catch (e: IncompatibleHostException) {
                System.err.println("context: ${e.message} — update this Sophi to use the shared index")
            } catch (e: Exception) {
                System.err.println("context: index unavailable (${e.message})")
            } finally { opened.countDown() }
        }, "sophi-context-open").apply { isDaemon = true }.start()
        writer.scheduleWithFixedDelay({ runCatching { drain() } }, drainEveryMillis, drainEveryMillis, TimeUnit.MILLISECONDS)
        return this
    }

    fun awaitReady(timeoutMillis: Long): Boolean = opened.await(timeoutMillis, TimeUnit.MILLISECONDS) && ready

    fun <T> withStoreOrNull(block: (ArcadeStore) -> T): T? {
        val h = host ?: return null
        return try { h.withStore(block) } catch (e: Exception) {
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
            val children = existing.filter { Path.of(it.root).startsWith(resolved) }
            children.forEach { c -> s.deleteDocument(ContextSchema.PROJECT, c.id); s.upsertDocument(ContextSchema.REQUEST, "drop:${c.id}", mapOf("projectId" to c.id, "kind" to "drop", "requestedAt" to System.currentTimeMillis())) }
            val id = ProjectRegistry.projectId(resolved)
            s.upsertDocument(ContextSchema.PROJECT, id, mapOf("root" to resolved.toString(), "name" to resolved.fileName.toString(), "status" to "active", "isGlobal" to false))
            requestScan(s, id)
            RegisterResult.Registered(projects(s).first { it.id == id }, children.map { it.id })
        } ?: RegisterResult.Refused(NOT_READY)
    }

    fun projects(): List<ProjectInfo> = withStoreOrNull { projects(it) } ?: emptyList()

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

    fun requestScan(projectId: String) { withStoreOrNull { requestScan(it, projectId) } }

    fun search(query: String, projectIds: List<String>, limit: Int = 8): List<SearchHit>? = withStoreOrNull { s ->
        val now = System.currentTimeMillis()
        projects(s).filter { it.id in projectIds && (it.lastScanAt == null || now - it.lastScanAt > scanInterval) }
            .forEach { requestScan(s, it.id) }
        Retriever(s).search(query, projectIds, limit)
    }

    fun fileIndexed(projectId: String, relPath: String): Boolean =
        withStoreOrNull { it.query("SELECT id FROM ${ContextSchema.FILE} WHERE id = ?", ContextSchema.fileId(projectId, relPath)).isNotEmpty() } ?: false

    fun fileSummaries(projectId: String): List<Triple<String, String, String?>> =
        withStoreOrNull { s -> s.query("SELECT path, role, summary FROM ${ContextSchema.FILE} WHERE projectId = ?", projectId)
            .map { Triple(it["path"] as String, it["role"] as String, it["summary"] as String?) } } ?: emptyList()

    override fun close() {
        closed = true
        writer.shutdownNow()
        runCatching { writer.awaitTermination(5, TimeUnit.SECONDS) }
        host?.let { runCatching { it.close() } }
        host = null
    }

    private fun requestScan(s: ArcadeStore, projectId: String) =
        s.upsertDocument(ContextSchema.REQUEST, projectId, mapOf("projectId" to projectId, "kind" to "scan", "requestedAt" to System.currentTimeMillis()))

    private fun projects(s: ArcadeStore): List<ProjectInfo> = s.documents(ContextSchema.PROJECT).map { r ->
        ProjectInfo(r["id"] as String, r["root"] as String, r["name"] as String, (r["status"] as String?) ?: "active",
            (r["isGlobal"] as Boolean?) ?: false, (r["lastScanAt"] as Number?)?.toLong(), (r["fileCount"] as Number?)?.toInt(),
            (r["partial"] as Boolean?) ?: false, (r["modules"] as List<*>?)?.map { it.toString() } ?: emptyList())
    }.sortedBy { it.root }

    /** Writer thread only. */
    private fun drain() {
        val h = host ?: return
        if (h.role != Role.HOST) { hostTermPrepared = false; return }
        h.withStore { s ->
            val indexer = Indexer(s, git)
            if (!hostTermPrepared) { indexer.rebuildIfStale(SCHEMA_VERSION); hostTermPrepared = true }
            s.documents(ContextSchema.REQUEST).sortedBy { (it["requestedAt"] as Number?)?.toLong() ?: 0L }.forEach { req ->
                if (closed) return@withStore
                val id = req["id"] as String
                val projectId = req["projectId"] as String
                s.deleteDocument(ContextSchema.REQUEST, id)
                if (req["kind"] == "drop") indexer.dropProject(projectId) else indexer.scan(projectId)
            }
        }
    }
}
