package dev.sophi.core.tools

import dev.sophi.core.audit.redactSecrets
import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

const val RESULT_READ_NAME = "result_read"
/** The most of one result the store keeps; bash and fetch_url stop collecting here too. */
const val MAX_RESULT_CHARS = 2_000_000
/** About 2k tokens: a few results per round still fit a 32k local window. */
const val DEFAULT_RESULT_PAGE_CHARS = 8_000
/** A floor for a configured 0 or a tiny window: below it a page shows too little to be worth a call. */
private const val MIN_RESULT_PAGE_CHARS = 500
private const val SESSION_CAP_BYTES = 50_000_000L
private val ID = Regex("r(\\d{1,9})")
private val FILE = Regex("r(\\d{1,9})\\.txt")

private fun n(i: Int) = String.format(Locale.ROOT, "%,d", i)

/**
 * What the model sees of [result]: the result itself when it fits [page] (floored at [MIN_RESULT_PAGE_CHARS]), otherwise
 * the head ⅔ and tail ⅓ of a page around a marker giving its size and, when [store] kept it, its id and how to page it.
 * The id is the second value.
 */
fun limitResult(result: String, page: Int, store: ResultStore?, sessionId: String): Pair<String, String?> {
    val pageChars = page.coerceAtLeast(MIN_RESULT_PAGE_CHARS)
    if (result.length <= pageChars) return result to null
    val kept = store?.put(sessionId, result, pageChars)
    val size = "${n(result.length)} chars, ${n(result.count { it == '\n' } + 1)} lines"
    val cut = if (result.length > MAX_RESULT_CHARS) "; only the first and last ${n(MAX_RESULT_CHARS / 2)} chars are kept" else ""
    val marker = if (kept == null) "[$size; showing the start and end; the rest was not kept]"
        else "[${kept.first}: $size; showing the start and end; $RESULT_READ_NAME(\"${kept.first}\", 2) for the next page of ${kept.second}$cut]"
    return "${result.take(pageChars * 2 / 3)}\n… $marker …\n${result.takeLast(pageChars / 3)}" to kept?.first
}

/**
 * Over-page tool results, whole, as `<dir>/<session>.results/r<n>.txt` (owner-only), so [ResultReadTool] can page them.
 * Ids count per session, across restarts. Past [sessionCapBytes] a session's oldest results are deleted first.
 * [pageChars] is the configured page; the limiter may use a smaller one for a small window.
 */
class ResultStore(
    private val dir: Path,
    val pageChars: Int = DEFAULT_RESULT_PAGE_CHARS,
    private val sessionCapBytes: Long = SESSION_CAP_BYTES,
) {
    private class Kept(val pageChars: Int, val bytes: Long)
    /** [kept] in id order, so the first is the oldest. */
    private class Results(var next: Int, val kept: LinkedHashMap<Int, Kept>)
    private val sessions = HashMap<String, Results>()

    private fun folder(sessionId: String): Path {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "bad session id" }
        return dir.resolve("$sessionId.results")
    }

    // ponytail: a result written before a restart pages at the configured size, not the window-capped one it was shown at
    private fun results(sessionId: String) = sessions.getOrPut(sessionId) {
        val files = runCatching { folder(sessionId).listDirectoryEntries() }.getOrDefault(emptyList())
            .mapNotNull { f -> FILE.matchEntire(f.fileName.toString())?.let { it.groupValues[1].toInt() to f } }.sortedBy { it.first }
        Results(next = (files.lastOrNull()?.first ?: 0) + 1,
            kept = files.associateTo(LinkedHashMap()) { (i, f) -> i to Kept(pageChars, runCatching { f.fileSize() }.getOrDefault(0)) })
    }

    /**
     * Keeps [result], secrets redacted and cut to its head and tail past [MAX_RESULT_CHARS]. Its id and page count, or
     * null when it couldn't be kept (redaction or the write failed): nothing unredacted is ever written.
     */
    @Synchronized
    fun put(sessionId: String, result: String, pageChars: Int): Pair<String, Int>? = runCatching {
        // ponytail: redaction runs inline, ~1 s per MB of output; move it off the call's path if big results get common
        val redacted = redactSecrets(result)
        val text = if (redacted.length <= MAX_RESULT_CHARS) redacted
            else redacted.take(MAX_RESULT_CHARS / 2) + "\n… [cut: ${n(redacted.length - MAX_RESULT_CHARS)} chars] …\n" + redacted.takeLast(MAX_RESULT_CHARS / 2)
        val bytes = text.toByteArray().size.toLong()
        val folder = folder(sessionId)
        val r = results(sessionId)
        var total = r.kept.values.sumOf { it.bytes }
        while (r.kept.isNotEmpty() && total + bytes > sessionCapBytes) {
            val (oldest, k) = r.kept.entries.first()
            r.kept.remove(oldest)
            folder.resolve("r$oldest.txt").deleteIfExists()
            total -= k.bytes
        }
        Files.createDirectories(folder)
        val i = r.next++
        val file = folder.resolve("r$i.txt")
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): the user profile's ACLs already apply.
        }
        file.writeText(text)
        r.kept[i] = Kept(pageChars, bytes)
        "r$i" to pageBounds(text, pageChars).size - 1
    }.getOrNull()

    /** Page [page] (1-based) of [id], headed `[r7 page 3/7]`, or an `Error: ` result. */
    @Synchronized
    fun read(sessionId: String, id: String, page: Int): String {
        val i = ID.matchEntire(id)?.groupValues?.get(1)?.toInt() ?: return "Error: no result $id; ids look like r7"
        val r = results(sessionId)
        val kept = r.kept[i] ?: return if (i < r.next) "Error: $id was evicted; run the call again" else "Error: no result $id in this session"
        val text = runCatching { folder(sessionId).resolve("r$i.txt").readText() }.getOrElse {
            r.kept.remove(i)
            return "Error: $id was evicted; run the call again"
        }
        val bounds = pageBounds(text, kept.pageChars)
        val pages = bounds.size - 1
        if (page !in 1..pages) return "Error: $id has $pages pages; ask for page 1 to $pages"
        return "[$id page $page/$pages]\n" + text.substring(bounds[page - 1], bounds[page])
    }

    /** Page starts plus the end: fixed slices of [size], cut after the last line break when one is within the last 10%. */
    private fun pageBounds(text: String, size: Int): List<Int> {
        val bounds = mutableListOf(0)
        var start = 0
        while (start + size < text.length) {
            val end = start + size
            val nl = text.lastIndexOf('\n', end - 1)
            start = if (nl >= end - size / 10 && nl >= start) nl + 1 else end
            bounds += start
        }
        bounds += text.length
        return bounds
    }
}

/** Pages a result the limiter kept. Its own output is never limited: one page always fits. */
class ResultReadTool(val store: ResultStore) : Tool {
    override val name = RESULT_READ_NAME
    override val description = "Read more of a long tool result that was shown cut, by the id its marker gives (e.g. r7)"
    override val parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"Result id, e.g. r7"},""" +
        """"page":{"type":"integer","description":"1-based page; default 2 (page 1 is what you already saw)"}},"required":["id"]}"""

    override suspend fun execute(argumentsJson: String): String {
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val id = args["id"]?.jsonPrimitive?.content ?: return "Error: id is required"
        val page = args["page"]?.jsonPrimitive?.let { it.intOrNull ?: return "Error: page is a whole number" } ?: 2
        return store.read(currentCoroutineContext()[SessionIdContext]?.sessionId ?: "default", id, page)
    }
}
