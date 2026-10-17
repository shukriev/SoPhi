package dev.sophi.context

data class ChunkDraft(val startLine: Int, val endLine: Int, val kind: String, val label: String?, val text: String)

data class SymbolDraft(val name: String, val kind: String, val line: Int)   // line is 1-based

data class Analysis(
    val chunks: List<ChunkDraft>, val summary: String?, val modules: List<String> = emptyList(),
    val symbols: List<SymbolDraft> = emptyList(),
    val imports: List<String> = emptyList(),   // raw, per family: "a.b.C", "./x", ".sib", "ex.com/r/pkg", "crate::a::b"
    val links: List<String> = emptyList()      // markdown only: "[[Note]]" (wikilink, stem or folder/stem) or a relative "x/y.md"
)

/**
 * Splits a file into retrievable chunks (spec §5.2 step 3). Regex declarations, not a parser:
 * tree-sitter waits until an experiment shows this is the bottleneck.
 */
object Analyzers {
    private const val WINDOW = 60
    private const val STEP = 50
    private const val MAX_CHUNK = 120
    private const val MAX_CHARS = 8_000
    private val LICENSE = Regex("(?i)copyright|licen[sc]e|spdx")

    // Top-level declarations only (no leading whitespace). Group 1 = name.
    private val DECL = mapOf(
        "kotlin" to Regex("""^(?:@[A-Za-z][\w.]*(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|inline|value|suspend|override|const|lateinit|operator|infix|tailrec|external|expect|actual|fun(?=\s+interface\b))\s+)*(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_]+(?:<[^>]*>)?\??\.)?([A-Za-z_][A-Za-z0-9_]*)"""),
        "java" to Regex("""^(?:@[A-Za-z][\w.]*(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*(?:class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "typescript" to Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|class|interface|type|enum|const|let)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""),
        "javascript" to Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|class|const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""),
        "python" to Regex("""^(?:async\s+)?(?:def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "go" to Regex("""^(?:func(?:\s*\([^)]*\))?|type)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "rust" to Regex("""^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:fn|struct|enum|trait|impl(?:<[^>]*>)?|mod|type)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "swift" to Regex("""^(?:(?:public|private|internal|fileprivate|open|final)\s+)*(?:func|class|struct|enum|protocol|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    )
    private val DOC_START = Regex("""^\s*(/\*\*|///|#\s|//)""")
    private val ANNOTATION = Regex("""^@[A-Za-z]""")

    private val KINDS = setOf("class", "interface", "object", "fun", "val", "var", "typealias", "enum", "record", "function",
        "type", "const", "let", "def", "func", "struct", "trait", "impl", "mod", "protocol", "extension")
    private val WORD = Regex("[A-Za-z]+")

    /** The last declaration keyword before the name ("data class Foo" → class, "val type" → val). */
    private fun kindOf(m: MatchResult): String =
        WORD.findAll(m.value.removeSuffix(m.groupValues[1])).map { it.value }.lastOrNull { it in KINDS } ?: "declaration"

    private val JVM_IMPORT = Regex("""^import\s+(?:static\s+)?([A-Za-z_][\w.]*?)(\.\*)?\s*(?:\bas\s+\w+)?\s*;?\s*(?://.*)?$""")
    private val JS_FROM = Regex("""(?:^|[\s;])(?:import|export)\b[^'"]*?\bfrom\s*['"]([^'"]+)['"]""")
    private val JS_CLOSE = Regex("""^\s*\}\s*from\s*['"]([^'"]+)['"]""")   // last line of a multi-line import/export { … } from
    private val JS_BARE = Regex("""^\s*import\s*['"]([^'"]+)['"]""")
    private val JS_REQUIRE = Regex("""\b(?:require|import)\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val PY_IMPORT = Regex("""^import\s+(.+)$""")
    private val PY_FROM = Regex("""^from\s+(\.*[\w.]*)\s+import\s+(.+)$""")
    private val GO_SINGLE = Regex("""^import\s+(?:[\w.]+\s+)?"([^"]+)"""")
    private val GO_LINE = Regex("""^\s*(?:[\w.]+\s+)?"([^"]+)"""")
    private val RUST_USE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([\w:]+)""")
    private val WIKI = Regex("""\[\[([^\]|#]+)(?:[#|][^\]]*)?\]\]""")
    private val MD_LINK = Regex("""\[[^\]]*\]\(\s*<?([^)\s>#]+\.md)(?:#[^)]*)?>?\s*\)""")

    private fun symbols(lang: String, lines: List<String>): List<SymbolDraft> {
        val decl = DECL[lang] ?: return emptyList()
        return lines.mapIndexedNotNull { i, l -> decl.find(l)?.let { m -> SymbolDraft(m.groupValues[1], kindOf(m), i + 1) } }
    }

    // ponytail: line regexes, so a multi-line Python `from x import (\n a,\n b)` yields only `x`; a parser waits for an experiment.
    private fun imports(lang: String, lines: List<String>): List<String> = when (lang) {
        "kotlin", "java" -> lines.mapNotNull { l -> JVM_IMPORT.find(l.trim())?.takeIf { it.groupValues[2].isEmpty() }?.groupValues?.get(1) }
        "typescript", "javascript" -> lines.flatMap { l -> listOf(JS_FROM, JS_CLOSE, JS_BARE, JS_REQUIRE).flatMap { r -> r.findAll(l).map { it.groupValues[1] } } }
        "python" -> lines.flatMap { l ->
            PY_FROM.find(l)?.let { m ->
                val mod = m.groupValues[1]
                if (mod.all { it == '.' }) m.groupValues[2].trim().removeSurrounding("(", ")").split(',')
                    .map { mod + it.trim().substringBefore(' ') }.filter { it.length > mod.length }
                else listOf(mod)
            } ?: PY_IMPORT.find(l)?.groupValues?.get(1)?.split(',')?.map { it.trim().substringBefore(' ') }.orEmpty()
        }
        "go" -> {
            var block = false
            lines.mapNotNull { l ->
                when {
                    l.startsWith("import (") -> { block = true; null }
                    block && l.trim() == ")" -> { block = false; null }
                    block -> GO_LINE.find(l)?.groupValues?.get(1)
                    else -> GO_SINGLE.find(l)?.groupValues?.get(1)
                }
            }
        }
        "rust" -> lines.mapNotNull { RUST_USE.find(it)?.groupValues?.get(1)?.removeSuffix("::") }
        else -> emptyList()
    }.filter { it.isNotEmpty() }.distinct()

    private fun links(text: String): List<String> =
        (WIKI.findAll(text).map { "[[" + it.groupValues[1].trim() + "]]" } +
            MD_LINK.findAll(text).map { it.groupValues[1] }.filter { "://" !in it && !it.startsWith("mailto:") }).distinct().toList()

    fun analyze(relPath: String, role: Role, lang: String, text: String): Analysis {
        if (role == Role.GENERATED) return Analysis(emptyList(), null)
        if (text.isBlank()) return Analysis(emptyList(), null)
        val lines = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val name = relPath.substringAfterLast('/')
        val modules = when {
            name == "pom.xml" -> Regex("<module>\\s*([^<\\s]+)\\s*</module>").findAll(text).map { it.groupValues[1] }.toList()
            name.startsWith("settings.gradle") -> Regex("""\binclude\b(?!Build)\s*(?:\(([^)]*)\)|([^\n]*))""").findAll(text)
                .flatMap { m -> Regex("""["']:?([^"']+)["']""").findAll(m.groupValues[1] + m.groupValues[2]).map { it.groupValues[1] } }.toList()
            else -> emptyList()
        }
        val chunks = when {
            lang == "markdown" -> sections(lines)
            lang in DECL -> declarations(lines, DECL.getValue(lang))
            else -> windows(lines, 1, lines.size, "window", null)
        }.flatMap { split(it, lines) }.flatMap { c ->
            if (c.text.length <= MAX_CHARS) listOf(c) else c.text.chunked(MAX_CHARS).map { c.copy(text = it) }
        }
        return Analysis(chunks, summary(lang, lines), modules, symbols(lang, lines), imports(lang, lines),
            if (lang == "markdown") links(text) else emptyList())
    }

    private class Start(val start: Int, val decl: Int, val label: String)

    private fun declStarts(lines: List<String>, decl: Regex): List<Start> {
        val starts = ArrayList<Start>()   // 0-based line index of the chunk start, declaration line, label
        lines.forEachIndexed { i, line ->
            val m = decl.find(line) ?: return@forEachIndexed
            val floor = starts.lastOrNull()?.start ?: -1   // never pull lines out of the previous declaration
            var s = i   // pull leading doc comments / annotations into the declaration
            while (s > floor + 1 && (DOC_START.containsMatchIn(lines[s - 1]) || ANNOTATION.containsMatchIn(lines[s - 1]) ||
                    lines[s - 1].trimStart().startsWith("*"))) s--
            starts.add(Start(s, i, m.groupValues[1]))
        }
        return starts
    }

    private fun declarations(lines: List<String>, decl: Regex): List<ChunkDraft> {
        val starts = declStarts(lines, decl).map { it.start to it.label }
        if (starts.isEmpty()) return windows(lines, 1, lines.size, "window", null)
        val out = ArrayList<ChunkDraft>()
        if (starts.first().first > 0 && lines.subList(0, starts.first().first).any { it.isNotBlank() })
            out.add(draft(lines, 0, starts.first().first - 1, "declaration", null))
        starts.forEachIndexed { k, (s, label) ->
            val end = if (k + 1 < starts.size) starts[k + 1].first - 1 else lines.size - 1
            out.add(draft(lines, s, end, "declaration", label))
        }
        return out
    }

    private fun sections(lines: List<String>): List<ChunkDraft> {
        val heads = lines.indices.filter { lines[it].startsWith("#") }
        if (heads.isEmpty()) return windows(lines, 1, lines.size, "window", null)
        val out = ArrayList<ChunkDraft>()
        if (heads.first() > 0 && lines.subList(0, heads.first()).any { it.isNotBlank() }) out.add(draft(lines, 0, heads.first() - 1, "section", null))
        heads.forEachIndexed { k, h ->
            val end = if (k + 1 < heads.size) heads[k + 1] - 1 else lines.size - 1
            out.add(draft(lines, h, end, "section", lines[h].trimStart('#').trim()))
        }
        return out
    }

    private fun windows(lines: List<String>, from: Int, to: Int, kind: String, label: String?): List<ChunkDraft> {
        if (to < from) return emptyList()
        val out = ArrayList<ChunkDraft>()
        var s = from
        while (true) {
            val e = minOf(s + WINDOW - 1, to)
            out.add(draft(lines, s - 1, e - 1, kind, label))
            if (e >= to) break
            s += STEP
        }
        return out
    }

    private fun split(c: ChunkDraft, lines: List<String>): List<ChunkDraft> =
        if (c.endLine - c.startLine + 1 <= MAX_CHUNK) listOf(c) else windows(lines, c.startLine, c.endLine, c.kind, c.label)

    private fun draft(lines: List<String>, from0: Int, to0: Int, kind: String, label: String?) =
        ChunkDraft(from0 + 1, to0 + 1, kind, label, lines.subList(from0, to0 + 1).joinToString("\n"))

    /** Doc comment directly above the first top-level declaration, else first non-licence doc comment; markdown: first paragraph. */
    private fun summary(lang: String, lines: List<String>): String? {
        if (lang == "markdown") {
            val para = lines.dropWhile { it.isBlank() || it.startsWith("#") }.takeWhile { it.isNotBlank() }.joinToString(" ").trim()
            return (para.ifEmpty { lines.firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim() })?.take(200)
        }
        val py = lang == "python"
        fun isDoc(l: String) = l.trimStart().let { it.startsWith("/**") || it.startsWith("///") || (py && it.startsWith("\"\"\"")) }
        fun docAt(i: Int): String? {
            val end = if (lines[i].trimStart().startsWith("///")) i else (i..minOf(i + 40, lines.size - 1)).firstOrNull { k ->
                if (k == i) lines[k].trimStart().removePrefix("/**").removePrefix("\"\"\"").contains(if (py) "\"\"\"" else "*/")
                else lines[k].contains(if (py) "\"\"\"" else "*/")
            } ?: i
            if (LICENSE.containsMatchIn(lines.subList(i, end + 1).joinToString("\n"))) return null
            val doc = lines[i]
            val first = doc.trim().removePrefix("/**").removePrefix("\"\"\"").removePrefix("///").removeSuffix("*/").removeSuffix("\"\"\"").trim()
                .ifEmpty { lines.getOrNull(i + 1)?.trim()?.removePrefix("*")?.trim().orEmpty() }
            return first.ifEmpty { null }?.take(200)
        }
        DECL[lang]?.let { decl ->
            val d = declStarts(lines, decl).firstOrNull()
            if (d != null) (d.start until d.decl).firstOrNull { isDoc(lines[it]) }?.let { docAt(it)?.let { r -> return r } }
        }
        return lines.indices.asSequence().filter { isDoc(lines[it]) }.firstNotNullOfOrNull { docAt(it) }
    }
}
