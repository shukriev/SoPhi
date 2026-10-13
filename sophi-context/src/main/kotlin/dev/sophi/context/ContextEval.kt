package dev.sophi.context

import dev.sophi.store.arcade.EmbeddedArcadeStore
import java.nio.file.Files
import java.nio.file.Path

data class EvalQuery(val text: String, val expected: List<String>)

data class EvalReport(
    val n: Int, val hitAt1: Double, val hitAt5: Double, val hitAt10: Double, val mrr: Double,
    val p50Ms: Double, val p95Ms: Double, val grepMrr: Double, val grepHitAt10: Double, val misses: List<String>
) {
    override fun toString() = buildString {
        appendLine("queries=$n  hit@1=%.2f  hit@5=%.2f  hit@10=%.2f  MRR=%.2f  p50=%.1fms  p95=%.1fms".format(hitAt1, hitAt5, hitAt10, mrr, p50Ms, p95Ms))
        appendLine("grep baseline: hit@10=%.2f  MRR=%.2f  (engine/grep MRR = %.1fx)".format(grepHitAt10, grepMrr, if (grepMrr > 0) mrr / grepMrr else Double.POSITIVE_INFINITY))
        misses.forEach { appendLine("  miss: $it") }
    }
}

data class HintPoint(val minScore: Double, val minRatio: Double, val coverage: Double, val precision: Double)

/**
 * Retrieval eval (spec §9): indexes [projects] into a throwaway store, so numbers are reproducible
 * and the user's ~/.sophi/db is never touched. The grep baseline mirrors the bake-off's: files
 * ranked by how many lines contain any query keyword.
 */
class ContextEval(
    private val projects: Map<String, Path>,
    private val git: GitProbe = GitProbe.system,
    private val weights: Weights = Weights()
) {
    companion object {
        fun load(tsv: Path): List<EvalQuery> = Files.readAllLines(tsv).map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('\t') }
            .map { line -> EvalQuery(line.substringBefore('\t'), line.substringAfter('\t').split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
    }

    /** Indexes [projects] into a throwaway store, runs [block], and always deletes the store. */
    private fun <T> withIndex(block: (Retriever, Map<String, String>) -> T): T {
        val dir = Files.createTempDirectory("sophi-context-eval")
        try {
            EmbeddedArcadeStore.open(dir).use { store ->
                ContextSchema.ensure(store)
                val indexer = Indexer(store, git)
                indexer.rebuildIfStale(ContextEngine.SCHEMA_VERSION)
                val names = HashMap<String, String>()
                projects.forEach { (name, root) ->
                    val id = ProjectRegistry.projectId(root.toRealPath())
                    names[id] = name
                    store.upsertDocument(ContextSchema.PROJECT, id, mapOf("root" to root.toRealPath().toString(), "name" to name, "status" to "active"))
                    indexer.scan(id)
                }
                return block(Retriever(store, weights), names)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    /** Hint-gate sweep (spec §6.7): one index, every (minScore, minRatio) point scored on the same top-3 hits. */
    fun hintGrid(queries: List<EvalQuery>, scores: List<Double>, ratios: List<Double>): List<HintPoint> = hintAnalysis(queries, scores, ratios).first

    /** [hintGrid] plus the top-1 score of every query (for judging τ against the corpus's score scale). */
    fun hintAnalysis(queries: List<EvalQuery>, scores: List<Double>, ratios: List<Double>): Pair<List<HintPoint>, List<Double>> = withIndex { retriever, names ->
        val ids = names.keys.toList()
        val searched = queries.map { q -> q to retriever.search(q.text, ids, limit = 3) }
        val grid = scores.flatMap { s -> ratios.map { r ->
            val gate = HintGate(3, s, r)
            val fired = searched.filter { (q, hits) -> gate.fires(q.text, hits) }
            val correct = fired.count { (q, hits) -> hits.any { h -> "${names[h.projectId]}/${h.path}".let { f -> q.expected.any { f.contains(it) } } } }
            HintPoint(s, r, fired.size / queries.size.coerceAtLeast(1).toDouble(), if (fired.isEmpty()) 1.0 else correct.toDouble() / fired.size)
        } }
        grid to searched.map { (_, hits) -> hits.firstOrNull()?.score ?: 0.0 }
    }

    fun run(queries: List<EvalQuery>): EvalReport {
        return withIndex { retriever, names ->
            run {
                val grepFiles = projects.flatMap { (name, root) -> ProjectFiles.list(root, git).files.map { "$name/${it.relPath}" to root.resolve(it.relPath) } }
                    .mapNotNull { (k, p) -> runCatching { k to Files.readAllLines(p).map { it.lowercase() } }.getOrNull() }
                var h1 = 0; var h5 = 0; var h10 = 0; var mrr = 0.0; var gH10 = 0; var gMrr = 0.0
                val lat = ArrayList<Double>(); val misses = ArrayList<String>()
                for (q in queries) {
                    var files: List<String> = emptyList()
                    repeat(3) { rep ->
                        val t0 = System.nanoTime()
                        files = retriever.search(q.text, names.keys.toList(), limit = 10).map { "${names[it.projectId]}/${it.path}" }
                        if (rep > 0) lat.add((System.nanoTime() - t0) / 1e6)
                    }
                    val rank = files.indexOfFirst { f -> q.expected.any { f.contains(it) } } + 1
                    if (rank == 1) h1++; if (rank in 1..5) h5++; if (rank in 1..10) h10++; if (rank > 0) mrr += 1.0 / rank
                    if (rank == 0 || rank > 5) misses += (if (rank == 0) "MISS " else "r$rank ") + q.text + " -> " + (files.firstOrNull() ?: "-")
                    val g = grep(q.text, grepFiles)
                    val gRank = g.indexOfFirst { f -> q.expected.any { f.contains(it) } } + 1
                    if (gRank in 1..10) gH10++; if (gRank in 1..10) gMrr += 1.0 / gRank
                }
                lat.sort()
                val n = queries.size.coerceAtLeast(1).toDouble()
                EvalReport(queries.size, h1 / n, h5 / n, h10 / n, mrr / n,
                    lat.getOrElse(lat.size / 2) { 0.0 }, lat.getOrElse((lat.size * 0.95).toInt()) { 0.0 }, gMrr / n, gH10 / n, misses)
            }
        }
    }

    private fun grep(query: String, files: List<Pair<String, List<String>>>): List<String> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        return files.map { (k, lines) -> k to lines.count { l -> words.any { l.contains(it) } } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(10).map { it.first }
    }
}
