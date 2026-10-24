package dev.sophi.learning.export

import dev.sophi.core.audit.redactTokens
import java.nio.file.Files
import java.nio.file.Path

class Redactor(userPatternsFile: Path? = null) {
    private val patterns: List<Pair<String, Regex>> = buildList {
        add("email" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))
        userPatternsFile?.takeIf { Files.exists(it) }?.let { f ->
            Files.readAllLines(f).filter { it.isNotBlank() }.forEach { line ->
                runCatching { add("custom" to Regex(line.trim())) }
            }
        }
    }

    private val hits = mutableMapOf<String, Int>()
    val hitsByType: Map<String, Int> get() = hits.toMap()

    fun redact(text: String): String {
        var out = redactTokens(text) { hits.merge(it, 1, Int::plus) }
        for ((type, regex) in patterns) {
            out = regex.replace(out) { hits.merge(type, 1, Int::plus); "[REDACTED:$type]" }
        }
        return out
    }
}
