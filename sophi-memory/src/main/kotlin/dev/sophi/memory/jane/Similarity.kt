package dev.sophi.memory.jane

import kotlin.math.sqrt

fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    val n = minOf(a.size, b.size)
    for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0.0
    return dot / (sqrt(na) * sqrt(nb))
}
