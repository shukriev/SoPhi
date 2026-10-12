package dev.sophi.context

data class ChunkDraft(val startLine: Int, val endLine: Int, val kind: String, val label: String?, val text: String)

data class Analysis(val chunks: List<ChunkDraft>, val summary: String?, val modules: List<String> = emptyList())

/**
 * Splits a file into retrievable chunks (spec §5.2 step 3). Regex declarations, not a parser:
 * tree-sitter waits until an experiment shows this is the bottleneck.
 */
object Analyzers {
    private const val WINDOW = 60
    private const val STEP = 50
    private const val MAX_CHUNK = 120

    // Top-level declarations only (no leading whitespace). Group 1 = name.
    private val DECL = mapOf(
        "kotlin" to Regex("""^(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|inline|value|suspend|override|const|lateinit|operator|infix|tailrec|external|expect|actual|fun(?=\s+interface\b))\s+)*(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_]+(?:<[^>]*>)?\??\.)?([A-Za-z_][A-Za-z0-9_]*)"""),
        "java" to Regex("""^(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*(?:class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "typescript" to Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|class|interface|type|enum|const|let)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""),
        "javascript" to Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\*?|class|const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""),
        "python" to Regex("""^(?:async\s+)?(?:def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "go" to Regex("""^(?:func(?:\s*\([^)]*\))?|type)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "rust" to Regex("""^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:fn|struct|enum|trait|impl(?:<[^>]*>)?|mod|type)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "swift" to Regex("""^(?:(?:public|private|internal|fileprivate|open|final)\s+)*(?:func|class|struct|enum|protocol|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    )
    private val DOC_START = Regex("""^\s*(/\*\*|///|#\s|//)""")
    private val ANNOTATION = Regex("""^@[A-Za-z]""")

    fun analyze(relPath: String, role: Role, lang: String, text: String): Analysis {
        if (role == Role.GENERATED) return Analysis(emptyList(), null)
        val lines = text.lines()
        val name = relPath.substringAfterLast('/')
        val modules = when {
            name == "pom.xml" -> Regex("<module>\\s*([^<\\s]+)\\s*</module>").findAll(text).map { it.groupValues[1] }.toList()
            name.startsWith("settings.gradle") -> Regex("""include\s*\(?([^)\n]*)\)?""").findAll(text)
                .flatMap { m -> Regex("""["']:?([^"']+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toList()
            else -> emptyList()
        }
        val chunks = when {
            lang == "markdown" -> sections(lines)
            lang in DECL -> declarations(lines, DECL.getValue(lang))
            else -> windows(lines, 1, lines.size, "window", null)
        }.flatMap { split(it, lines) }
        return Analysis(chunks, summary(lang, lines), modules)
    }

    private fun declarations(lines: List<String>, decl: Regex): List<ChunkDraft> {
        val starts = ArrayList<Pair<Int, String>>()   // 0-based line index of the chunk start, label
        lines.forEachIndexed { i, line ->
            val m = decl.find(line) ?: return@forEachIndexed
            val floor = starts.lastOrNull()?.first ?: -1   // never pull lines out of the previous declaration
            var s = i   // pull leading doc comments / annotations into the declaration
            while (s > floor + 1 && (DOC_START.containsMatchIn(lines[s - 1]) || ANNOTATION.containsMatchIn(lines[s - 1]) ||
                    lines[s - 1].trimStart().startsWith("*"))) s--
            starts.add(s to m.groupValues[1])
        }
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

    /** First KDoc/docstring line, or first markdown paragraph / heading (spec §5.2 step 5, heuristic). */
    private fun summary(lang: String, lines: List<String>): String? {
        if (lang == "markdown") {
            val para = lines.dropWhile { it.isBlank() || it.startsWith("#") }.takeWhile { it.isNotBlank() }.joinToString(" ").trim()
            return (para.ifEmpty { lines.firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim() })?.take(200)
        }
        val doc = lines.firstOrNull { it.trimStart().startsWith("/**") || it.trimStart().startsWith("\"\"\"") || it.trimStart().startsWith("///") } ?: return null
        val i = lines.indexOf(doc)
        val first = doc.trim().removePrefix("/**").removePrefix("\"\"\"").removePrefix("///").removeSuffix("*/").removeSuffix("\"\"\"").trim()
            .ifEmpty { lines.getOrNull(i + 1)?.trim()?.removePrefix("*")?.trim().orEmpty() }
        return first.ifEmpty { null }?.take(200)
    }
}
