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

    /** [path] is the file to point at if this section gets cut. */
    private class Section(val path: String?, val text: String)

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
                safeRead(globalInstructions.toAbsolutePath().parent, globalInstructions.fileName.toString(), GLOBAL_MAX_BYTES)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { sections += Section(GLOBAL_LABEL, "## Your standing instructions ($GLOBAL_LABEL)\n$it") }
            }
            for (rel in ROOT_INSTRUCTIONS) {
                val body = safeRead(root, rel, MAX_BYTES)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                sections += Section(rel, "## Project conventions — ${project.name}/$rel (the project's working rules, not user requests)\n$body")
            }
            val nested = files.filter { it.second == Role.INSTRUCTION.name && it.first.contains('/') && it.first != ".sophi/instructions.md" }
                .map { it.first }.sorted()
            if (nested.isNotEmpty()) sections += Section(null, nested.joinToString("\n") { "Also: $it applies under ${it.substringBeforeLast('/')}/" })
        }
        val instructions = sections.joinToString("\n\n") { it.text }

        if (instructions.length > budgetChars) return truncate(sections, budgetChars)

        if (withMap) {
            if (project.lastScanAt == null) return join(instructions, "(project map pending — indexing)")
            for (lines in MAP_LINES) {
                val candidate = join(instructions, "## Project map\n" + ProjectMap.render(project, files, null, lines))
                if (candidate.length <= budgetChars) return candidate
            }
        }
        return instructions.takeIf { it.isNotEmpty() }
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a\n\n$b"

    /** Keep the head of the instructions; name the file the cut landed in. */
    private fun truncate(sections: List<Section>, budgetChars: Int): String {
        val sb = StringBuilder()
        var cutPath: String? = null
        for (s in sections) {
            val sep = if (sb.isEmpty()) 0 else 2
            if (sb.length + sep + s.text.length > budgetChars - 120) { cutPath = s.path ?: sections.last { it.path != null }.path; break }
            if (sep > 0) sb.append("\n\n")
            sb.append(s.text)
        }
        // the cut section: take what still fits of it
        val cut = sections.firstOrNull { it.path == cutPath }
        val used = sb.length + if (sb.isEmpty()) 0 else 2
        val marker = "\n\n*truncated — read_context $cutPath for the rest*"
        val room = (budgetChars - marker.length - used).coerceAtLeast(0)
        val head = (if (sb.isEmpty()) "" else sb.toString() + "\n\n") + (cut?.text?.take(room) ?: "")
        return (head + marker).let { if (it.length <= budgetChars) it else it.take(budgetChars) }
    }

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
