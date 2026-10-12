package dev.sophi.core.tools

import java.nio.file.Path

/** Places outside the workspace a command may always name: scratch space and device files. */
private val ALWAYS_ALLOWED = listOf("/tmp", "/private/tmp", "/dev", "/var/folders", "/private/var/folders")
private val OPERATORS = setOf(";", "&&", "||", "|", "&")
private val REDIRECT_PREFIX = Regex("""^\d*(>>|>|<)&?""")

/**
 * The first path a shell [command] names outside [root] (as written), or null when it stays inside.
 * The program word of each `;`/`&&`/`||`/`|` segment is not a path it reaches (`/opt/homebrew/bin/node`
 * is fine). `~` and `$HOME` expand to [home]; `..` resolves against [root]; /tmp and /dev are allowed.
 *
 * A heuristic, not a sandbox: a command that builds a path at runtime (variables, `$(...)`, scripts)
 * is not seen. It closes the quiet case — a read-only `cat ~/.ssh/id_rsa` rated CAUTION — and lets the
 * security level ask.
 */
fun bashReachesOutside(command: String, root: Path, home: String = System.getProperty("user.home")): String? {
    val workspace = root.toAbsolutePath().normalize()
    var programNext = true
    for (raw in tokenize(command)) {
        if (raw in OPERATORS) { programNext = true; continue }
        if (programNext) { programNext = false; continue }
        val token = REDIRECT_PREFIX.replace(raw, "").substringAfter('=', missingDelimiterValue = "").ifEmpty {
            REDIRECT_PREFIX.replace(raw, "")
        }
        val expanded = when {
            token == "~" || token.startsWith("~/") -> home + token.removePrefix("~")
            token.startsWith("\$HOME") -> home + token.removePrefix("\$HOME")
            token.startsWith("\${HOME}") -> home + token.removePrefix("\${HOME}")
            else -> token
        }
        val looksLikePath = expanded.startsWith("/") || expanded.split('/').contains("..")
        if (!looksLikePath) continue
        val resolved = workspace.resolve(expanded).normalize()
        val allowed = resolved.startsWith(workspace) ||
            ALWAYS_ALLOWED.any { resolved == Path.of(it) || resolved.startsWith(Path.of(it)) }
        if (!allowed) return token
    }
    return null
}

/** Words of a shell command with quotes removed; `;`, `&&`, `||`, `|`, `&` become their own tokens. */
private fun tokenize(command: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() } }
    var i = 0
    while (i < command.length) {
        val c = command[i]
        when {
            quote != null -> if (c == quote) quote = null else cur.append(c)
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> flush()
            c == ';' || c == '|' || c == '&' -> {
                // "2>&1" is a redirect, not an operator
                if (c == '&' && cur.endsWith(">")) { cur.append(c); i++; continue }
                flush()
                val two = command.getOrNull(i + 1)
                if ((c == '&' || c == '|') && two == c) { out.add("$c$c"); i++ } else out.add(c.toString())
            }
            else -> cur.append(c)
        }
        i++
    }
    flush()
    return out
}
