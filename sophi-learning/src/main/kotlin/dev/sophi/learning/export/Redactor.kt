package dev.sophi.learning.export

import java.nio.file.Files
import java.nio.file.Path

class Redactor(userPatternsFile: Path? = null) {
    private val patterns: List<Pair<String, Regex>> = buildList {
        add("private_key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"))
        add("github_token" to Regex("gh[pousr]_[A-Za-z0-9]{20,}"))
        add("aws_key" to Regex("AKIA[0-9A-Z]{16}"))
        add("slack_token" to Regex("xox[baprs]-[A-Za-z0-9-]{10,}"))
        add("jwt" to Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"))
        add("api_key" to Regex("sk-[A-Za-z0-9]{20,}"))
        add("bearer" to Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}"))
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
        var out = text
        for ((type, regex) in patterns) {
            out = regex.replace(out) { hits.merge(type, 1, Int::plus); "[REDACTED:$type]" }
        }
        return out
    }
}
