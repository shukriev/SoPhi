package dev.sophi.core.permissions

/** `*` matches any run of characters (including `/`); `\*` and `\\` are literals; everything else is literal. The whole text must match. */
fun globMatches(pattern: String, text: String): Boolean {
    val regex = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '*' -> regex.append(".*")
            c == '\\' && i + 1 < pattern.length && pattern[i + 1] in "*\\" -> regex.append(Regex.escape(pattern[++i].toString()))
            else -> regex.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL).matches(text)
}

/** A pattern that matches exactly [text]. */
fun globEscape(text: String): String = text.replace("\\", "\\\\").replace("*", "\\*")
