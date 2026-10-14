package dev.sophi.context

import org.tartarus.snowball.ext.EnglishStemmer

/**
 * One tokenizer for both index and query (spec §5.2 step 4) — the bake-off showed ranking quality
 * comes from this plus BM25, not from the database. Bump [VERSION] whenever output changes: the host
 * re-derives every project's chunks when the stored version differs.
 */
object Tokenizer {
    const val VERSION = 1

    private val STOP = setOf(
        "the", "a", "an", "of", "to", "in", "is", "and", "or", "for", "on", "when", "how", "what", "why", "do", "i",
        "it", "we", "that", "this", "be", "are", "with", "as", "by", "at", "from", "not", "did", "use", "gets", "too",
        "new", "may", "still", "over", "up", "out", "after", "their", "there", "they", "which", "where", "does", "its",
        "was", "has", "have", "can", "if", "else", "val", "var", "fun", "return", "import", "package", "null", "true",
        "false", "string", "int"
    )
    private val SPLIT = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])")
    private val NON_ALNUM = Regex("[^A-Za-z0-9]+")
    // Snowball stemmers keep state between calls — one per thread.
    private val stemmer = ThreadLocal.withInitial { EnglishStemmer() }

    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split(NON_ALNUM)) {
            if (raw.isEmpty()) continue
            val parts = raw.split(SPLIT)
            if (parts.size > 1) emit(out, raw.lowercase())
            parts.forEach { emit(out, it.lowercase()) }
        }
        return out
    }

    fun indexText(path: String, text: String): String = tokens("$path\n$text").joinToString(" ")

    private fun emit(out: MutableList<String>, word: String) {
        if (word.length < 2 || word in STOP) return
        val s = stemmer.get()
        s.current = word
        s.stem()
        out.add(s.current)
    }
}
