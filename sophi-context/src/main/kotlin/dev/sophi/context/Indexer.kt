package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ScanStats(val added: Int, val changed: Int, val removed: Int, val unchanged: Int, val partial: Boolean)

/**
 * Brings one project's index in line with its files (spec §5.2). Host-only and single-threaded by
 * contract (ContextEngine runs every call on its one writer thread): TermStat/ContextMeta are
 * read-modify-write, so two concurrent indexers would lose updates.
 * Callers must run [rebuildIfStale] before the first [scan] (ContextEngine does).
 */
class Indexer(
    private val store: ArcadeStore,
    private val git: GitProbe = GitProbe.system,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxFiles: Int = ProjectFiles.MAX_FILES,
    private val analyze: (String, Role, String, String) -> Analysis = Analyzers::analyze
) {
    fun rebuildIfStale(schemaVersion: Int) {
        val m = ContextSchema.meta(store)
        if (m != null && m.schemaVersion == schemaVersion && m.tokenizerVersion == Tokenizer.VERSION) return
        store.transaction {
            listOf(ContextSchema.FILE, ContextSchema.CHUNK, ContextSchema.TERM).forEach { store.deleteAll(it) }
            ContextSchema.putMeta(store, Meta(schemaVersion, Tokenizer.VERSION, 0, 0))
        }
    }

    fun scan(projectId: String): ScanStats {
        val project = store.query("SELECT FROM ${ContextSchema.PROJECT} WHERE id = ?", projectId).firstOrNull()
            ?: return ScanStats(0, 0, 0, 0, false)
        val root = Path.of(project["root"] as String)
        if (!Files.isDirectory(root)) {
            store.command("UPDATE ${ContextSchema.PROJECT} SET status = 'missing' WHERE id = ?", projectId)
            return ScanStats(0, 0, 0, 0, false)
        }
        val listing = ProjectFiles.list(root, git, maxFiles)
        val known = store.query("SELECT id, path, size, mtime, contentHash FROM ${ContextSchema.FILE} WHERE projectId = ?", projectId)
            .associateBy { it["path"] as String }
        var added = 0; var changed = 0; var unchanged = 0
        for (f in listing.files) {
            val old = known[f.relPath]
            if (old != null && (old["size"] as Number).toLong() == f.size && (old["mtime"] as Number).toLong() == f.mtime) { unchanged++; continue }
            val ok = runCatching {
                // unreadable existing file keeps its old chunks: transient errors shouldn't delete
                val bytes = runCatching { Files.readAllBytes(root.resolve(f.relPath)) }.getOrNull() ?: return@runCatching null
                val hash = sha256(bytes)
                if (old != null && old["contentHash"] == hash) {
                    store.command("UPDATE ${ContextSchema.FILE} SET mtime = ?, size = ? WHERE id = ?", f.mtime, f.size, old["id"])
                    unchanged++
                } else {
                    replaceFile(projectId, f, String(bytes, Charsets.UTF_8), hash)
                    if (old == null) added++ else changed++
                }
            }.onFailure { System.err.println("context: skipped ${f.relPath}: ${it.message}") }
        }
        val present = listing.files.mapTo(HashSet()) { it.relPath }
        val gone = if (listing.partial) emptyList() else known.keys.filter { it !in present }
        val removedOk = gone.count { rel ->
            runCatching { removeFile(ContextSchema.fileId(projectId, rel)) }
                .onFailure { System.err.println("context: skipped removal of $rel: ${it.message}") }.isSuccess
        }
        val modules = listing.files.filter { !it.relPath.contains('/') && (it.relPath == "pom.xml" || it.relPath.startsWith("settings.gradle")) }
            .flatMap { f -> runCatching { Analyzers.analyze(f.relPath, f.role, f.lang, Files.readString(root.resolve(f.relPath))).modules }.getOrDefault(emptyList()) }
        store.command("UPDATE ${ContextSchema.PROJECT} SET lastScanAt = ?, fileCount = ?, partial = ?, modules = ? WHERE id = ?",
            now(), listing.files.size, listing.partial, modules, projectId)
        store.command("UPDATE ${ContextSchema.PROJECT} SET status = 'active' WHERE id = ? AND status = 'missing'", projectId)
        return ScanStats(added, changed, removedOk, unchanged, listing.partial)
    }

    fun dropProject(projectId: String) {
        store.query("SELECT id FROM ${ContextSchema.FILE} WHERE projectId = ?", projectId).forEach { removeFile(it["id"] as String) }
        store.deleteDocument(ContextSchema.PROJECT, projectId)
    }

    private fun replaceFile(projectId: String, f: FileEntry, text: String, hash: String) {
        val fileId = ContextSchema.fileId(projectId, f.relPath)
        val analysis = analyze(f.relPath, f.role, f.lang, text)
        val drafts = analysis.chunks.map { c -> c to Tokenizer.indexText(f.relPath, listOfNotNull(c.label, c.text).joinToString("\n")) }
        store.transaction {
            val (oldTerms, oldCount, oldLen) = deleteChunks(fileId)
            val newTerms = HashMap<String, Int>()
            var newLen = 0L
            drafts.forEachIndexed { i, (c, toks) ->
                val terms = toks.split(' ').filter { it.isNotEmpty() }
                terms.toSet().forEach { newTerms.merge(it, 1, Int::plus) }
                newLen += terms.size
                store.upsertDocument(ContextSchema.CHUNK, ContextSchema.chunkId(fileId, i), mapOf(
                    "projectId" to projectId, "fileId" to fileId, "path" to f.relPath, "role" to f.role.name,
                    "startLine" to c.startLine, "endLine" to c.endLine, "kind" to c.kind, "label" to c.label,
                    "text" to c.text, "toks" to toks, "len" to terms.size, "chunkHash" to sha256(c.text.toByteArray())))
            }
            applyDf(oldTerms, newTerms)
            bumpMeta(drafts.size - oldCount, newLen - oldLen)
            store.upsertDocument(ContextSchema.FILE, fileId, mapOf(
                "projectId" to projectId, "path" to f.relPath, "lang" to f.lang, "role" to f.role.name,
                "size" to f.size, "mtime" to f.mtime, "contentHash" to hash, "summary" to analysis.summary))
        }
    }

    private fun removeFile(fileId: String) = store.transaction {
        val (oldTerms, oldCount, oldLen) = deleteChunks(fileId)
        applyDf(oldTerms, emptyMap())
        bumpMeta(-oldCount, -oldLen)
        store.deleteDocument(ContextSchema.FILE, fileId)
    }

    /** Deletes a file's chunks; returns (per-term chunk counts, chunk count, total token length) they contributed. */
    private fun deleteChunks(fileId: String): Triple<Map<String, Int>, Int, Long> {
        val old = store.query("SELECT toks, len FROM ${ContextSchema.CHUNK} WHERE fileId = ?", fileId)
        val terms = HashMap<String, Int>()
        old.forEach { r -> (r["toks"] as String).split(' ').filter { it.isNotEmpty() }.toSet().forEach { terms.merge(it, 1, Int::plus) } }
        if (old.isNotEmpty()) store.command("DELETE FROM ${ContextSchema.CHUNK} WHERE fileId = ?", fileId)
        return Triple(terms, old.size, old.sumOf { (it["len"] as Number).toLong() })
    }

    // ponytail: one read + one upsert per distinct term per changed file; initial indexing of a
    // large repo is bounded by this. Batch by file group if E7 shows it dominates.
    private fun applyDf(removed: Map<String, Int>, added: Map<String, Int>) {
        val delta = HashMap<String, Int>(added)
        removed.forEach { (t, n) -> delta.merge(t, -n, Int::plus) }
        val touched = delta.filterValues { it != 0 }
        if (touched.isEmpty()) return
        val current = store.query("SELECT id, df FROM ${ContextSchema.TERM} WHERE id IN ?", touched.keys.toList())
            .associate { it["id"] as String to (it["df"] as Number).toInt() }
        touched.forEach { (t, d) ->
            val v = (current[t] ?: 0) + d
            if (v <= 0) store.deleteDocument(ContextSchema.TERM, t) else store.upsertDocument(ContextSchema.TERM, t, mapOf("df" to v))
        }
    }

    private fun bumpMeta(chunks: Int, len: Long) {
        val m = ContextSchema.meta(store) ?: Meta(0, Tokenizer.VERSION, 0, 0)
        ContextSchema.putMeta(store, m.copy(chunkCount = maxOf(0, m.chunkCount + chunks), totalLen = maxOf(0, m.totalLen + len)))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
