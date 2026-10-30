package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.pathSubject
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class ApplyPatchArgs(val patch: String)

/** One line of a hunk: ' ' context, '-' removed, '+' added. */
internal data class HunkLine(val kind: Char, val text: String)

internal data class Hunk(val header: String?, val lines: List<HunkLine>, val atEof: Boolean) {
    val old: List<String> get() = lines.filter { it.kind != '+' }.map { it.text }
}

internal sealed interface FileOp {
    val path: String
    data class Add(override val path: String, val lines: List<String>) : FileOp
    data class Update(override val path: String, val moveTo: String?, val hunks: List<Hunk>) : FileOp
    data class Delete(override val path: String) : FileOp
}

internal class PatchException(message: String) : Exception(message)

/** Parses the Codex/OpenCode `*** Begin Patch` envelope; throws [PatchException] naming what's wrong. */
internal fun parsePatch(text: String): List<FileOp> {
    val all = text.replace("\r\n", "\n").lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    if (all.firstOrNull()?.trim() != "*** Begin Patch") throw PatchException("a patch starts with the line \"*** Begin Patch\"")
    if (all.size < 2 || all.last().trim() != "*** End Patch") throw PatchException("a patch ends with the line \"*** End Patch\"")
    val lines = all.subList(1, all.size - 1)
    val ops = mutableListOf<FileOp>()
    var i = 0
    fun body(): List<String> {
        val out = mutableListOf<String>()
        while (i < lines.size && !(lines[i].startsWith("*** ") && lines[i].trim() != "*** End of File")) out += lines[i++]
        return out
    }
    while (i < lines.size) {
        val line = lines[i++]
        when {
            line.startsWith("*** Add File: ") -> {
                val path = line.removePrefix("*** Add File: ").trim()
                val content = body().map { l ->
                    if (!l.startsWith("+")) throw PatchException("in Add File $path every line starts with '+', got: $l")
                    l.substring(1)
                }
                ops += FileOp.Add(path, content)
            }
            line.startsWith("*** Delete File: ") -> ops += FileOp.Delete(line.removePrefix("*** Delete File: ").trim())
            line.startsWith("*** Update File: ") -> {
                val path = line.removePrefix("*** Update File: ").trim()
                val moveTo = lines.getOrNull(i)?.takeIf { it.startsWith("*** Move to: ") }?.let { i++; it.removePrefix("*** Move to: ").trim() }
                ops += FileOp.Update(path, moveTo, parseHunks(path, body()))
            }
            line.isBlank() -> {}
            else -> throw PatchException("expected \"*** Add File:\", \"*** Update File:\" or \"*** Delete File:\", got: $line")
        }
    }
    if (ops.isEmpty()) throw PatchException("the patch changes no files")
    return ops
}

private fun parseHunks(path: String, body: List<String>): List<Hunk> {
    val hunks = mutableListOf<Hunk>()
    var header: String? = null
    var current = mutableListOf<HunkLine>()
    var eof = false
    var started = false
    fun flush() {
        if (current.isNotEmpty()) hunks += Hunk(header, current, eof)
        current = mutableListOf(); eof = false
    }
    for (l in body) {
        when {
            l.startsWith("@@") -> { if (started) flush(); started = true; header = l.removePrefix("@@").trim().ifEmpty { null } }
            l.trim() == "*** End of File" -> eof = true
            else -> {
                started = true
                current += when {
                    l.isEmpty() -> HunkLine(' ', "") // models drop the space of an empty context line
                    l[0] == ' ' || l[0] == '-' || l[0] == '+' -> HunkLine(l[0], l.substring(1))
                    else -> throw PatchException("in Update File $path a hunk line starts with ' ', '-' or '+', got: $l")
                }
            }
        }
    }
    flush()
    if (hunks.isEmpty()) throw PatchException("Update File $path has no hunks")
    return hunks
}

internal data class SeekMatch(val index: Int, val pass: Int)

/** Codex's seek_sequence: exact, then ignoring trailing, then all surrounding whitespace; with [eof] the end is tried first. */
internal fun seek(lines: List<String>, pattern: List<String>, start: Int, eof: Boolean): SeekMatch? {
    if (pattern.isEmpty()) return SeekMatch(if (eof) lines.size else start.coerceAtMost(lines.size), 1)
    val norms = listOf<(String) -> String>({ it }, { it.trimEnd() }, { it.trim() })
    norms.forEachIndexed { pass, n ->
        fun at(i: Int) = i >= 0 && i + pattern.size <= lines.size && pattern.indices.all { n(lines[i + it]) == n(pattern[it]) }
        if (eof && at(lines.size - pattern.size)) return SeekMatch(lines.size - pattern.size, pass + 1)
        for (i in start..lines.size - pattern.size) if (at(i)) return SeekMatch(i, pass + 1)
    }
    return null
}

private fun leading(s: String) = s.takeWhile { it == ' ' || it == '\t' }

/** [content] with [hunks] applied; throws [PatchException] naming the hunk that didn't match. */
internal fun applyHunks(path: String, content: String, hunks: List<Hunk>): Pair<String, Pair<Int, Int>> {
    val crlf = content.contains("\r\n")
    val text = content.replace("\r\n", "\n")
    val trailingNewline = text.endsWith("\n")
    val lines = (if (trailingNewline) text.dropLast(1) else text).let { if (it.isEmpty() && text.isEmpty()) emptyList() else it.split("\n") }
    val replacements = mutableListOf<Triple<Int, Int, List<String>>>()
    var cursor = 0
    var added = 0
    var removed = 0
    hunks.forEachIndexed { n, h ->
        var start = cursor
        h.header?.let { hdr -> (cursor until lines.size).firstOrNull { lines[it].trim() == hdr }?.let { start = it + 1 } }
        val old = h.old
        val m = seek(lines, old, start, h.atEof) ?: throw PatchException(missMessage(path, n + 1, old, lines, start))
        // Pass 3 matched ignoring indentation: give the added lines the file's indentation too.
        val indent = if (m.pass == 3 && old.isNotEmpty()) {
            val file = leading(lines[m.index]); val patch = leading(old.first())
            if (file.startsWith(patch)) file.removePrefix(patch) else ""
        } else ""
        var fileAt = m.index
        val out = mutableListOf<String>()
        for (l in h.lines) when (l.kind) {
            ' ' -> out += lines[fileAt++]
            '-' -> { fileAt++; removed++ }
            else -> { out += if (l.text.isEmpty()) "" else indent + l.text; added++ }
        }
        replacements += Triple(m.index, old.size, out)
        cursor = m.index + old.size
    }
    val result = lines.toMutableList()
    for ((at, size, repl) in replacements.asReversed()) {
        repeat(size) { result.removeAt(at) }
        result.addAll(at, repl)
    }
    var joined = result.joinToString("\n") + if (trailingNewline) "\n" else ""
    if (crlf) joined = joined.replace("\n", "\r\n")
    return joined to (added to removed)
}

private fun missMessage(path: String, hunk: Int, old: List<String>, lines: List<String>, start: Int): String {
    val near = old.firstOrNull { it.isNotBlank() }?.trim()?.let { first -> (start until lines.size).firstOrNull { lines[it].trim() == first } }
    val region = near?.let { lines.subList((it - 2).coerceAtLeast(0), (it + 3).coerceAtMost(lines.size)).joinToString("\n") }
    return buildString {
        append("$path hunk $hunk: these lines aren't in the file:\n${old.joinToString("\n")}")
        if (region != null) append("\nClosest part of the file:\n$region")
        append("\nRead the file again and resend the whole patch; nothing was changed.")
    }
}

/**
 * Adds, updates, moves and deletes several files in one call, all or nothing: every hunk is matched in memory
 * before anything is written. Deletes go to the SoPhi trash, like delete_file.
 */
class ApplyPatchTool(
    private val root: Path,
    private val trashDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "trash"),
    private val home: Path = Path.of(System.getProperty("user.home")),
) : Tool {
    override val name = "apply_patch"
    override val description = """
        Change several files in one call: add, update, move and delete, all or nothing. Format:
        *** Begin Patch
        *** Add File: path/new.txt
        +every line starts with +
        *** Update File: path/old.txt
        *** Move to: path/renamed.txt   (optional)
        @@ optional line to search from, e.g. a function signature
         context line (starts with a space)
        -removed line
        +added line
        *** Delete File: path/gone.txt
        *** End Patch
        Give 1-3 context lines around each change. Paths are relative to the working directory.
    """.trimIndent()
    override val parametersJson = """
        {"type":"object","properties":{"patch":{"type":"string","description":"The whole patch, from *** Begin Patch to *** End Patch"}},"required":["patch"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    private fun ops(argumentsJson: String): List<FileOp>? =
        runCatching { parsePatch(json.decodeFromString<ApplyPatchArgs>(argumentsJson).patch) }.getOrNull()

    private fun paths(op: FileOp) = listOfNotNull(op.path, (op as? FileOp.Update)?.moveTo)

    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE

    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        // Unparseable: execute() fails before touching anything, so don't prompt (as edit_file).
        return ops(argumentsJson)?.flatMap(::paths)?.map { classifyPathRisk(root, it) }?.reduce(::worst) ?: RuleVerdict.LOW_RISK
    }

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? {
        val subjects = ops(argumentsJson)?.flatMap(::paths)?.map { pathSubject(root, root.resolve(it).normalize()) } ?: return null
        return if (subjects.any { it == null }) null else subjects.filterNotNull().distinct()
    }

    override fun confirmationPreview(argumentsJson: String): String? = ops(argumentsJson)?.joinToString("\n") { op ->
        when (op) {
            is FileOp.Add -> "A ${op.path} (+${op.lines.size})"
            is FileOp.Delete -> "D ${op.path}"
            is FileOp.Update -> (if (op.moveTo != null) "R ${op.path} → ${op.moveTo}" else "M ${op.path}") + "\n" +
                op.hunks.joinToString("\n") { h -> (listOfNotNull(h.header?.let { "@@ $it" }) + h.lines.map { "${it.kind}${it.text}" }).joinToString("\n") }
        }
    }

    private fun resolve(raw: String): Path {
        val p = root.resolve(raw).normalize()
        if (!p.startsWith(root)) throw PatchException("$raw is outside the working directory")
        return p
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString<ApplyPatchArgs>(argumentsJson) }
            .getOrElse { return "Error: arguments must be {\"patch\": \"*** Begin Patch\\n...\\n*** End Patch\"}" }
        val writes = mutableListOf<Pair<Path, String>>()
        val moves = mutableListOf<Pair<Path, Path>>()
        val deletes = mutableListOf<Path>()
        val summary = mutableListOf<String>()
        try {
            val ops = parsePatch(args.patch)
            val seen = mutableSetOf<Path>()
            for (op in ops) {
                val p = resolve(op.path)
                val target = (op as? FileOp.Update)?.moveTo?.let(::resolve)
                for (q in listOfNotNull(p, target)) if (!seen.add(q)) throw PatchException("${root.relativize(q)} is touched twice in one patch")
                when (op) {
                    is FileOp.Add -> {
                        if (p.exists()) throw PatchException("Add File ${op.path}: it already exists (use Update File)")
                        writes += p to op.lines.joinToString("\n", postfix = "\n")
                        summary += "A ${op.path}"
                    }
                    is FileOp.Delete -> {
                        if (!p.isRegularFile()) throw PatchException("Delete File ${op.path}: no such file (apply_patch deletes files, not folders)")
                        deletes.add(p)
                        summary += "D ${op.path}"
                    }
                    is FileOp.Update -> {
                        if (!p.isRegularFile()) throw PatchException("Update File ${op.path}: no such file")
                        if (target != null && target.exists()) throw PatchException("Move to ${op.moveTo}: it already exists")
                        val (content, counts) = applyHunks(op.path, p.readText(), op.hunks)
                        writes += p to content
                        if (target != null) { moves += p to target; summary += "R ${op.path} → ${op.moveTo}" }
                        else summary += "M ${op.path} (+${counts.first} -${counts.second})"
                    }
                }
            }
        } catch (e: PatchException) {
            return "Error: ${e.message}"
        }
        // Everything matched: now write, then move, then delete.
        for ((p, content) in writes) { p.parent?.let { Files.createDirectories(it) }; p.writeText(content) }
        for ((from, to) in moves) moveAndRecord(root, home, from, to)
        for (p in deletes) moveToTrash(root, home, p, trashDir)
        val result = summary.joinToString("\n")
        val source = (writes.map { it.first } + moves.map { it.second }).firstOrNull()?.let { root.relativize(it).toString() }
        return source?.let { withTestHint(result, it) } ?: result
    }
}
