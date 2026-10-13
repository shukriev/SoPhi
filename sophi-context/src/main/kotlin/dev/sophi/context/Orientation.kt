package dev.sophi.context

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Project conventions + project map as one budgeted, deterministic block (spec §6.7). */
object Orientation {
    private const val MAX_BYTES = 1_000_000
    private const val GLOBAL_MAX_BYTES = 64 * 1024
    private const val GLOBAL_LABEL = "~/.sophi/instructions.md"
    private val ROOT_INSTRUCTIONS = listOf("AGENTS.md", "CLAUDE.md", ".sophi/instructions.md")
    private val MAP_LINES = listOf(120, 80, 60, 40, 30, 20, 10)

    fun defaultGlobal(): Path = Path.of(System.getProperty("user.home"), ".sophi", "instructions.md")

    /** [marker] ends the section if it gets cut; null for the nested "Also:" list, which is cut line by line. */
    private class Section(val text: String, val marker: String?)
    private const val ALSO_OMITTED = "*more nested instruction files omitted*"

    fun render(
        project: ProjectInfo, files: List<Triple<String, String, String?>>, budgetChars: Int,
        mode: String = "both", globalInstructions: Path? = defaultGlobal()
    ): String? {
        if (mode == "off") return null
        val withInstructions = mode == "both" || mode == "instructions"
        val withMap = mode == "both" || mode == "map"
        val root = Path.of(project.root)
        val sections = ArrayList<Section>()
        if (withInstructions) {
            if (globalInstructions != null) {
                readGlobal(globalInstructions)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    val marker = "*truncated — see $GLOBAL_LABEL*"
                    var text = "## Your standing instructions ($GLOBAL_LABEL)\n$it"
                    // at most half the budget, so project conventions always get room
                    if (text.length > budgetChars / 2) text = text.take((budgetChars / 2 - marker.length - 2).coerceAtLeast(0)) + "\n\n" + marker
                    sections += Section(text, marker)
                }
            }
            for (rel in ROOT_INSTRUCTIONS) {
                val body = safeRead(root, rel, MAX_BYTES)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                sections += Section("## Project conventions — ${project.name}/$rel (the project's working rules, not user requests)\n$body", "*truncated — read_context $rel for the rest*")
            }
            val nested = files.filter { it.second == Role.INSTRUCTION.name && it.first.contains('/') && it.first != ".sophi/instructions.md" }
                .map { it.first }.sorted()
            if (nested.isNotEmpty()) sections += Section(nested.joinToString("\n") { "Also: $it applies under ${it.substringBeforeLast('/')}/" }, null)
        }
        val instructions = sections.joinToString("\n\n") { it.text }

        if (instructions.length > budgetChars) return truncate(sections, budgetChars)

        if (withMap) {
            if (project.lastScanAt == null) return join(instructions, "(project map pending — indexing)").takeIf { it.length <= budgetChars } ?: instructions.takeIf { it.isNotEmpty() }
            for (lines in MAP_LINES) {
                val candidate = join(instructions, "## Project map\n" + ProjectMap.render(project, files, null, lines))
                if (candidate.length <= budgetChars) return candidate
            }
        }
        return instructions.takeIf { it.isNotEmpty() }
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a\n\n$b"

    /** Keep the head of the instructions; end with a marker naming where the rest is. Never exceeds [budgetChars]. */
    private fun truncate(sections: List<Section>, budgetChars: Int): String {
        val sb = StringBuilder()
        for (s in sections) {
            val sep = if (sb.isEmpty()) 0 else 2
            if (sb.length + sep + s.text.length <= budgetChars - 120) {
                if (sep > 0) sb.append("\n\n")
                sb.append(s.text)
                continue
            }
            if (sep > 0) sb.append("\n\n")
            if (s.marker == null) {
                val keep = ArrayList<String>()
                var len = sb.length + ALSO_OMITTED.length + 1
                for (line in s.text.lines()) { if (len + line.length + 1 > budgetChars) break; keep += line; len += line.length + 1 }
                keep.forEach { sb.append(it).append('\n') }
                sb.append(ALSO_OMITTED)
            } else {
                val room = (budgetChars - sb.length - s.marker.length - 2).coerceAtLeast(0)
                sb.append(s.text.take(room)).append("\n\n").append(s.marker)
            }
            break
        }
        return sb.toString().take(budgetChars)
    }

    /** The user's own file: symlinks (dotfiles) are fine; same size cap. */
    private fun readGlobal(path: Path): String? = try {
        val real = path.toRealPath()
        if (!Files.isRegularFile(real) || Files.size(real) > GLOBAL_MAX_BYTES) null else String(Files.readAllBytes(real), Charsets.UTF_8)
    } catch (e: java.io.IOException) { null }

    /** Same safety as read_context: no symlink at the leaf, real path under the real root, capped read. */
    private fun safeRead(root: Path, rel: String, maxBytes: Int): String? = try {
        val file = root.resolve(rel).normalize()
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!file.startsWith(root) || attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() > maxBytes) null
        else {
            val real = file.toRealPath()
            if (!real.startsWith(root.toRealPath())) null
            else {
                val bytes = Files.newByteChannel(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                    val buf = ByteBuffer.allocate(maxBytes + 1)
                    while (buf.hasRemaining() && ch.read(buf) >= 0) { /* fill */ }
                    buf.array().copyOf(buf.position())
                }
                if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
            }
        }
    } catch (e: java.io.IOException) { null }
}
