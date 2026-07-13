package dev.sophi.memory.jane

import kotlin.math.sqrt

fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    val n = minOf(a.size, b.size)
    for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0.0
    return dot / (sqrt(na) * sqrt(nb))
}

data class Scored(val id: String, val score: Double)

/**
 * In-memory brute-force cosine index. ~20k vectors ≈ milliseconds per query (spec §5);
 * deliberately no ANN library. Thread-confined to the palace's own dispatcher.
 */
class EmbeddingIndex(initial: Map<String, FloatArray> = emptyMap()) {
    private val vectors = HashMap<String, FloatArray>(initial)

    fun put(id: String, vector: FloatArray) { vectors[id] = vector }
    fun remove(id: String) { vectors.remove(id) }
    fun get(id: String): FloatArray? = vectors[id]
    fun ids(): Set<String> = vectors.keys.toSet()

    fun nearest(query: FloatArray, k: Int, candidateIds: Set<String>? = null): List<Scored> =
        vectors.asSequence()
            .filter { candidateIds == null || it.key in candidateIds }
            .map { Scored(it.key, cosine(query, it.value)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
}
