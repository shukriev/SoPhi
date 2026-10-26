package dev.sophi.core.permissions

/**
 * One simple command out of a compound bash line; [redirects] is true when it writes to a file (not `/dev/null` or an fd dup),
 * [assigns] when a leading `NAME=value` was stripped from [text] (`LD_PRELOAD=x git status` must not count as `git status`).
 */
data class BashPart(val text: String, val redirects: Boolean, val assigns: Boolean = false)

private val KEYWORDS = setOf("if", "then", "else", "elif", "do", "while", "until", "fi", "done", "!", "time", "{", "}")
private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
private val FD_DUP = Regex("^&([0-9]+|-)(?=[\\s;&|)<>]|$)")
private val REDIRECT_WORD = Regex("^([0-9]*|&)(>>?|<{1,3})[&|]?")
private val CASE_WORD = Regex("\\bcase\\b")
private val NULL_TARGET = Regex("^\\s*/dev/null(?=[\\s;&|)]|$)")
private val BACKTICK_ESCAPE = Regex("""\\([\\`$])""")
private val UNMATCHABLE_WORD_CHARS = "\\'\"$`{"

/**
 * Splits a command on `; & | newline ( )` outside quotes and recurses into `$(..)`, backticks and `<(..)`/`>(..)`,
 * so a deny rule can't be dodged with compound syntax. Returns null when it can't split safely (unterminated quote
 * or substitution, heredoc, `$'..'`, `case` inside a substitution, stray `)`, or a command word that is quoted, escaped
 * or expanded, like `\rm`, `"rm"`, `$RM`, `{rm,x}`); callers apply R3: deny if any deny rule
 * exists for the tool, else ask if any ask rule exists, else no rule applies.
 * ponytail: hand-rolled scanner covers common shell; tree-sitter-bash is the upgrade path for full grammar.
 */
fun splitBashCommand(command: String): List<BashPart>? {
    val s = command
    val out = mutableListOf<BashPart>()
    val pending = mutableListOf<BashPart>()
    val cur = StringBuilder()
    var redirects = false
    var sawRedirect = false
    var unmatchable = false
    var quote = ' '
    var groups = 0
    var i = 0

    fun flush() {
        val raw = cur.toString().trim()
        val (text, assigns) = stripLeading(raw)
        if (text.substring(0, wordEnd(text)).any { it in UNMATCHABLE_WORD_CHARS }) unmatchable = true
        // a redirect-only part (`> ~/.bashrc`) must not vanish: it is kept as written so rules can see it
        if (text.isNotEmpty()) out += BashPart(text, redirects, assigns) else if (sawRedirect) out += BashPart(raw, redirects, assigns)
        out += pending
        pending.clear()
        cur.clear()
        redirects = false
        sawRedirect = false
    }

    // Copies the substitution opened at [opener] into the part and queues its inner parts; returns the index after it, or -1.
    fun substitution(opener: Int, openLen: Int): Int {
        val backtick = s[opener] == '`'
        val close = if (backtick) findBacktick(s, opener + 1) else findParen(s, opener + openLen)
        if (close < 0) return -1
        val inner = s.substring(opener + openLen, close)
        if (!backtick && CASE_WORD.containsMatchIn(inner)) return -1 // case arms contain a bare `)`
        // bash un-escapes \` \$ \\ in a backtick body before running it: `echo \`rm x\`` runs rm
        pending += splitBashCommand(if (backtick) inner.replace(BACKTICK_ESCAPE, "$1") else inner) ?: return -1
        cur.append(s, opener, close + 1)
        return close + 1
    }

    while (i < s.length) {
        val c = s[i]
        if (quote == '\'') {
            cur.append(c)
            if (c == '\'') quote = ' '
            i++
            continue
        }
        if (c == '\\') {
            cur.append(c)
            if (i + 1 < s.length) cur.append(s[i + 1])
            i += 2
            continue
        }
        if (c == '`' || (c == '$' && s.startsWith("$(", i))) {
            i = substitution(i, if (c == '`') 1 else 2).also { if (it < 0) return null }
            continue
        }
        if (quote == '"') {
            cur.append(c)
            if (c == '"') quote = ' '
            i++
            continue
        }
        when {
            c == '\'' || c == '"' -> { quote = c; cur.append(c); i++ }
            (c == '<' || c == '>') && s.startsWith("(", i + 1) -> i = substitution(i, 2).also { if (it < 0) return null }
            c == '$' && s.startsWith("$'", i) -> return null
            c == '{' && (cur.isEmpty() || cur.last() == ' ') && (i + 1 == s.length || s[i + 1].isWhitespace()) -> { flush(); i++ }
            c == '(' -> { groups++; flush(); i++ }
            c == ')' -> { if (groups-- == 0) return null; flush(); i++ }
            c == '<' && s.startsWith("<<<", i) -> { cur.append("<<<"); i += 3 }
            c == '<' && s.startsWith("<<", i) -> return null
            c == '>' -> {
                cur.append(c)
                sawRedirect = true
                i++
                if (i < s.length && s[i] == '>') continue
                val rest = s.substring(i)
                if (!FD_DUP.containsMatchIn(rest) && !NULL_TARGET.containsMatchIn(rest)) redirects = true
            }
            (c == '&' || c == '|') && (cur.lastOrNull().let { it == '<' || it == '>' } || (c == '&' && s.startsWith(">", i + 1))) -> { cur.append(c); i++ }
            c in ";&|\n" -> { flush(); i++ }
            c == ' ' || c == '\t' -> { if (cur.isNotEmpty() && cur.last() != ' ') cur.append(' '); i++ }
            else -> { cur.append(c); i++ }
        }
    }
    if (quote != ' ') return null
    flush()
    return out.takeUnless { unmatchable }
}

/** Drops leading shell keywords (and `time -p`), `NAME=value` assignments and redirections, leaving the command word first; true when an assignment was dropped. */
private fun stripLeading(text: String): Pair<String, Boolean> {
    var t = text
    var assigns = false
    while (true) {
        val word = t.substring(0, wordEnd(t))
        val rest = t.substring(word.length).trimStart()
        when {
            word == "time" && rest.substring(0, wordEnd(rest)) == "-p" -> t = rest.substring(2).trimStart()
            word in KEYWORDS -> t = rest
            ASSIGNMENT.containsMatchIn(word) -> { t = rest; assigns = true }
            REDIRECT_WORD.containsMatchIn(word) && !word.startsWith("<(") && !word.startsWith(">(") ->
                // a bare operator takes the next word as its target
                t = if (REDIRECT_WORD.replaceFirst(word, "").isEmpty()) rest.substring(wordEnd(rest)).trimStart() else rest
            else -> return t to assigns
        }
    }
}

private fun wordEnd(t: String): Int {
    var q = ' '
    var depth = 0
    var i = 0
    while (i < t.length) {
        val c = t[i]
        when {
            c == '\\' && q != '\'' -> i++
            q != ' ' -> if (c == q) q = ' '
            c == '\'' || c == '"' -> q = c
            c == '(' -> depth++
            c == ')' -> depth--
            c == ' ' && depth <= 0 -> return i
        }
        i++
    }
    return t.length
}

/** Index of the `)` closing a `(` whose content starts at [from], or -1. */
private fun findParen(s: String, from: Int): Int {
    var q = ' '
    var depth = 1
    var i = from
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && q != '\'' -> i++
            q != ' ' -> if (c == q) q = ' '
            c == '\'' || c == '"' -> q = c
            c == '(' -> depth++
            c == ')' -> if (--depth == 0) return i
        }
        i++
    }
    return -1
}

/** Index of the unescaped backtick at or after [from], or -1. */
private fun findBacktick(s: String, from: Int): Int {
    var i = from
    while (i < s.length) {
        if (s[i] == '\\') i++ else if (s[i] == '`') return i
        i++
    }
    return -1
}
