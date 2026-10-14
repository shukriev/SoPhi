package dev.sophi.context

/** A compact, budget-bounded picture of a project (spec §6.3 item 2; M2 adds token budgeting). */
object ProjectMap {
    fun render(project: ProjectInfo, files: List<Triple<String, String, String?>>, under: String? = null, maxLines: Int = 120): String {
        val prefix = under?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: ""
        val scoped = files.filter { it.first.startsWith(prefix) }.sortedBy { it.first }
        val c = ::cleanPromptText
        val out = ArrayList<String>()
        out += "# ${c(project.name)}${if (prefix.isNotEmpty()) " / ${c(prefix)}" else ""}  (${c(project.root)})"
        if (project.partial) out += "(partially indexed — over the file cap)"
        val langs = scoped.groupingBy { langOf(it.first) }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(6)
        if (langs.isNotEmpty()) out += "Languages: " + langs.joinToString(" · ") { "${it.key} ${it.value}" }
        if (prefix.isEmpty() && project.modules.isNotEmpty()) out += "Modules: " + project.modules.joinToString(", ")
        val instructions = scoped.filter { it.second == Role.INSTRUCTION.name }.map { it.first }
        if (instructions.isNotEmpty()) out += "Instruction files: " + instructions.joinToString(", ") { c(it) }
        out += "Layout:"
        val rel = scoped.map { it.first.removePrefix(prefix) }
        rel.filter { it.contains('/') }.groupingBy { it.substringBefore('/') }.eachCount().toSortedMap().forEach { (top, n) ->
            out += "  ${c(top)}/ ($n files)"
            rel.filter { it.startsWith("$top/") && it.removePrefix("$top/").contains('/') }
                .groupingBy { it.removePrefix("$top/").substringBefore('/') }.eachCount().toSortedMap()
                .forEach { (second, m) -> out += "    ${c(second)}/ ($m)" }
        }
        val key = scoped.filter { f ->
            val name = f.first.substringAfterLast('/')
            f.second == Role.INSTRUCTION.name || name.startsWith("README", ignoreCase = true) || (f.second == Role.CONFIG.name && !f.first.removePrefix(prefix).contains('/'))
        }
        if (key.isNotEmpty()) {
            out += "Key files:"
            key.sortedBy { it.first }.forEach { (p, _, s) -> out += "  ${c(p)}" + (s?.let { " — ${c(it)}" } ?: "") }
        }
        return if (out.size <= maxLines) out.joinToString("\n") else (out.take(maxLines - 1) + "… (${out.size - maxLines + 1} more lines — call project_map with a path)").joinToString("\n")
    }

    private fun langOf(path: String) = ProjectFiles.classify(path).second
}
