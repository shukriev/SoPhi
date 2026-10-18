package dev.sophi.context

/**
 * Turns raw imports/links into file-to-file edges within one project (spec §5.2 step 3, ADR-041 §10).
 * Regex in, path matching out — not a compiler: an import that names no file of this project is dropped.
 */
object Relations {
    const val IMPORTS = "IMPORTS"
    const val LINKS = "LINKS"
    const val TESTS = "TESTS"

    data class Source(val path: String, val lang: String, val role: Role, val imports: List<String>, val links: List<String>)
    data class Edge(val from: String, val to: String, val kind: String)

    class PathIndex(paths: Collection<String>) {
        val all: Set<String> = paths.toHashSet()
        private val byName: Map<String, List<String>> = all.groupBy { it.substringAfterLast('/') }
        private val byStem: Map<String, List<String>> = all.filter { it.endsWith(".md") }
            .groupBy { it.substringAfterLast('/').removeSuffix(".md").lowercase() }
        val goDirs: Map<String, List<String>> = all.filter { it.endsWith(".go") && !it.endsWith("_test.go") }
            .groupBy { it.substringBeforeLast('/', "") }
        fun suffix(rel: String): List<String> = byName[rel.substringAfterLast('/')].orEmpty().filter { it == rel || it.endsWith("/$rel") }
        fun stem(name: String): List<String> = byStem[name.lowercase()].orEmpty()
    }

    private val TEST_NAME = listOf(
        Regex("""^(.+?)(?:Test|Tests|Spec|IT)\.(?:kt|kts|java)$"""),
        Regex("""^(.+?)\.(?:test|spec)\.(?:ts|tsx|js|jsx|mjs)$"""),
        Regex("""^test_(.+)\.py$"""),
        Regex("""^(.+)_test\.(?:py|go)$""")
    )

    private val ASSET_EXT = Regex("""\.(?:png|jpe?g|gif|svg|webp|pdf)$""", RegexOption.IGNORE_CASE)

    fun all(files: List<Source>): List<Edge> {
        val index = PathIndex(files.map { it.path })
        return files.flatMap { outgoing(it, index) } + tests(files)
    }

    fun outgoing(f: Source, index: PathIndex): List<Edge> =
        (f.imports.mapNotNull { resolveImport(f, it, index) }.map { Edge(f.path, it, IMPORTS) } +
            f.links.mapNotNull { resolveLink(f, it, index) }.map { Edge(f.path, it, LINKS) })
            .filter { it.to != f.path }.distinct()

    fun tests(files: List<Source>): List<Edge> {
        val sources = files.filter { it.role == Role.SOURCE }.groupBy { it.path.substringAfterLast('/').substringBefore('.').lowercase() }
        return files.mapNotNull { t ->
            val stem = TEST_NAME.firstNotNullOfOrNull { it.find(t.path.substringAfterLast('/'))?.groupValues?.get(1) } ?: return@mapNotNull null
            val target = closest(t.path, sources[stem.lowercase()].orEmpty().filter { it.lang == t.lang && it.path != t.path }.map { it.path })
            target?.let { Edge(t.path, it, TESTS) }
        }
    }

    private fun resolveImport(f: Source, raw: String, index: PathIndex): String? {
        val dir = f.path.substringBeforeLast('/', "")
        fun exact(cands: List<String>) = cands.firstOrNull { it in index.all }
        fun bySuffix(cands: List<String>) = cands.firstNotNullOfOrNull { c -> closest(f.path, index.suffix(c)) }
        return when (f.lang) {
            "kotlin", "java" -> raw.split('.').let { parts ->
                bySuffix(listOf(parts, parts.dropLast(1)).filter { it.size >= 2 }.flatMap { p -> listOf("kt", "java").map { e -> p.joinToString("/") + ".$e" } })
            }
            "typescript", "javascript" -> if (!raw.startsWith(".")) null else normalize(dir, raw)?.let { base ->
                exact(listOf(base) + listOf("ts", "tsx", "js", "jsx", "mjs").map { "$base.$it" } + listOf("ts", "tsx", "js").map { "$base/index.$it" })
            }
            "python" -> if (raw.substringBefore('.') == "__future__") null else if (raw.startsWith(".")) {
                val dots = raw.takeWhile { it == '.' }.length
                var base = dir
                repeat(dots - 1) { base = base.substringBeforeLast('/', "") }
                val p = listOf(base, raw.drop(dots).replace('.', '/')).filter { it.isNotEmpty() }.joinToString("/")
                exact(listOf("$p.py", "$p/__init__.py"))
            } else raw.replace('.', '/').let { bySuffix(listOf("$it.py", "$it/__init__.py")) }
            "go" -> raw.split('/').let { segs ->
                (segs.size downTo 2).asSequence().map { k -> segs.takeLast(k).joinToString("/") }
                    .firstNotNullOfOrNull { suf -> closest(f.path, index.goDirs.keys.filter { it == suf || it.endsWith("/$suf") }) }
                    ?.let { index.goDirs.getValue(it).min() }
            }
            "rust" -> if (!raw.startsWith("crate::")) null else raw.removePrefix("crate::").split("::").let { segs ->
                bySuffix(listOf(segs, segs.dropLast(1)).filter { it.isNotEmpty() }.flatMap { s ->
                    s.joinToString("/").let { listOf("$it.rs", "$it/mod.rs") }
                })
            }
            else -> null
        }
    }

    private fun resolveLink(f: Source, raw: String, index: PathIndex): String? =
        if (raw.startsWith("[[")) raw.removeSurrounding("[[", "]]").substringAfterLast('/').trim().let { name ->
            if (ASSET_EXT.containsMatchIn(name)) null else closest(f.path, index.stem(name.removeSuffix(".md")))
        }
        else normalize(f.path.substringBeforeLast('/', ""), raw.replace("%20", " "))?.takeIf { it in index.all }

    /** [rel] joined onto [dir], `.`/`..` collapsed; null if it climbs above the project root. A leading `/` is root-relative. */
    private fun normalize(dir: String, rel: String): String? {
        val out = ArrayList<String>()
        if (!rel.startsWith("/")) out.addAll(dir.split('/').filter { it.isNotEmpty() })
        for (part in rel.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
            else -> out.add(part)
        }
        return out.joinToString("/").ifEmpty { null }
    }

    private fun closest(from: String, candidates: List<String>): String? =
        candidates.maxWithOrNull(compareBy<String> { from.commonPrefixWith(it).length }.thenByDescending { it.length })
}
