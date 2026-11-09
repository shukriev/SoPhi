package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.pathSubject
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

// ponytail: zip/jar only (java.util.zip); tar/tar.gz needs a reader of its own and comes when asked, bash `tar` covers it until then.

const val ARCHIVE_MAX_ENTRIES = 10_000
const val ARCHIVE_MAX_TOTAL_BYTES = 100_000_000L
/** Equals CheckpointStore's default maxFileBytes, so every extracted file stays inside what /undo snapshots. */
const val ARCHIVE_MAX_FILE_BYTES = 10_000_000L
private const val LIST_CAP = 500

@Serializable
private data class ArchiveListArgs(val path: String)

@Serializable
private data class ArchiveExtractArgs(val path: String, val to: String)

private val archiveJson = Json { ignoreUnknownKeys = true }
private val DRIVE_LETTER = Regex("^[A-Za-z]:")

/** [raw] resolved inside [root], like read_file; also refused when it really lives outside through a symlinked directory. */
private fun insideRoot(root: Path, raw: String): Path {
    val p = root.resolve(raw).normalize()
    require(p.startsWith(root) && realInside(root, p)) { "Path escapes working directory: $raw" }
    return p
}

/** The nearest existing ancestor of [p] (a dangling link fails) has its real path inside [root]'s. */
private fun realInside(root: Path, p: Path): Boolean = runCatching {
    var existing = p
    while (!existing.exists(LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return false
    existing.toRealPath().startsWith(root.toRealPath())
}.getOrDefault(false)

/** An entry name archive_extract refuses: absolute, drive-lettered, a `..` segment, a backslash or NUL, or landing outside [base]. */
private fun unsafeName(base: Path, name: String): Boolean =
    name.startsWith("/") || DRIVE_LETTER.containsMatchIn(name) || '\\' in name || '\u0000' in name ||
        name.split('/').any { it == ".." } || !base.resolve(name).normalize().startsWith(base)

private fun openZip(file: Path): ZipFile? = try { ZipFile(file.toFile()) } catch (_: ZipException) { null }

private fun notZip(raw: String) = "${TOOL_ERROR_PREFIX}Not a zip archive: $raw"

/** Copies until EOF or just past [cap], returning the bytes actually streamed (never the header's claim). */
private fun copyCapped(input: InputStream, out: OutputStream, cap: Long): Long {
    val buf = ByteArray(64 * 1024)
    var n = 0L
    while (n <= cap) {
        val r = input.read(buf)
        if (r < 0) break
        out.write(buf, 0, r)
        n += r
    }
    return n
}

private fun names(list: List<String>) = list.take(5).joinToString(", ") + if (list.size > 5) " (+${list.size - 5} more)" else ""

class ArchiveListTool(private val root: Path = Path.of("").toAbsolutePath()) : Tool {

    override val name = "archive_list"
    override val description = "List the entries of a zip or jar archive in the working directory, flagging any archive_extract would refuse."
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Archive path, relative to the working directory"}},"required":["path"]}
    """.trimIndent()

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        runCatching { archiveJson.decodeFromString<ArchiveListArgs>(argumentsJson) }.getOrNull()
            ?.let { pathSubject(root, root.resolve(it.path).normalize()) }?.let { listOf(it) }

    override suspend fun execute(argumentsJson: String): String {
        val args = archiveJson.decodeFromString<ArchiveListArgs>(argumentsJson)
        val zip = openZip(insideRoot(root, args.path)) ?: return notZip(args.path)
        return zip.use { z ->
            val lines = z.stream().limit(LIST_CAP.toLong()).map { e ->
                val flag = if (unsafeName(root, e.name)) "  [unsafe: archive_extract refuses this archive]" else ""
                if (e.isDirectory) "${e.name} (dir)$flag" else "${e.name}  ${e.size} bytes declared$flag"
            }.toList()
            val more = if (z.size() > LIST_CAP) listOf("… ${z.size() - LIST_CAP} more") else emptyList()
            (listOf("${z.size()} entries in ${args.path}") + lines + more).joinToString("\n")
        }
    }
}

class ArchiveExtractTool(
    private val root: Path = Path.of("").toAbsolutePath(),
    private val maxEntries: Int = ARCHIVE_MAX_ENTRIES,
    private val maxTotalBytes: Long = ARCHIVE_MAX_TOTAL_BYTES,
    private val maxFileBytes: Long = ARCHIVE_MAX_FILE_BYTES,
) : Tool {

    override val name = "archive_extract"
    override val description = "Extract a zip or jar archive into a folder in the working directory; never overwrites existing files, and can be undone with /undo."
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Archive path, relative to the working directory"},"to":{"type":"string","description":"Folder to extract into, relative to the working directory; created if missing"}},"required":["path","to"]}
    """.trimIndent()

    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override fun effects(argumentsJson: String) = setOf(EffectKind.WRITES)

    private fun args(argumentsJson: String) = runCatching { archiveJson.decodeFromString<ArchiveExtractArgs>(argumentsJson) }.getOrNull()

    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val a = args(argumentsJson) ?: return RuleVerdict.LOW_RISK
        return worst(classifyPathRisk(root, a.path), classifyPathRisk(root, a.to))
    }

    override fun confirmationPreview(argumentsJson: String): String? {
        val a = args(argumentsJson) ?: return null
        return runCatching {
            openZip(insideRoot(root, a.path))?.use { z ->
                val files = z.stream().filter { !it.isDirectory }.toList()
                val mb = files.sumOf { maxOf(it.size, 0L) } / 1_000_000.0
                "Extract ${files.size} files (${String.format(Locale.ROOT, "%.1f", mb)} MB declared) from ${a.path} into ${a.to}/"
            }
        }.getOrNull()
    }

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        args(argumentsJson)?.let { a ->
            val subjects = listOf(a.path, a.to).map { pathSubject(root, root.resolve(it).normalize()) }
            if (subjects.any { it == null }) null else subjects.filterNotNull()
        }

    override suspend fun execute(argumentsJson: String): String {
        val a = archiveJson.decodeFromString<ArchiveExtractArgs>(argumentsJson)
        val file = insideRoot(root, a.path)
        val to = insideRoot(root, a.to)
        val zip = openZip(file) ?: return notZip(a.path)
        return zip.use { z ->
            require(z.size() <= maxEntries) { "Archive has ${z.size()} entries, over the $maxEntries-entry limit" }
            val entries = z.entries().toList()

            // Pass 1 writes nothing: names, conflicts and real streamed sizes, so any refusal leaves the disk untouched.
            val unsafe = entries.map { it.name }.filter { unsafeName(to, it) || !realInside(root, to.resolve(it).normalize()) }
            require(unsafe.isEmpty()) { "Refusing to extract ${a.path}: unsafe entry names ${names(unsafe)}" }
            val files = entries.filterNot { it.isDirectory }
            val duplicates = files.groupBy { to.resolve(it.name).normalize() }.filterValues { it.size > 1 }.values.map { it.first().name }
            // A folder an entry needs (its parents, or a directory entry itself) must not be an existing file or another entry's file.
            val fileTargets = files.map { to.resolve(it.name).normalize() }.toSet()
            val blocked = entries.filter { e ->
                val t = to.resolve(e.name).normalize()
                generateSequence(if (e.isDirectory) t else t.parent) { it.parent }.takeWhile { it != to && it.startsWith(to) }
                    .any { it in fileTargets || (it.exists(LinkOption.NOFOLLOW_LINKS) && !it.isDirectory()) }
            }.map { it.name }
            val conflicts = files.map { it.name }.filter { to.resolve(it).normalize().exists(LinkOption.NOFOLLOW_LINKS) } + duplicates + blocked
            require(conflicts.isEmpty()) { "Refusing to overwrite existing files: ${names(conflicts)}" }
            var total = 0L
            files.forEach { e ->
                val n = z.getInputStream(e).use { copyCapped(it, OutputStream.nullOutputStream(), maxFileBytes) }
                require(n <= maxFileBytes) { "Entry ${e.name} is over the $maxFileBytes-byte per-file limit" }
                total += n
                require(total <= maxTotalBytes) { "Archive is over the $maxTotalBytes-byte total limit" }
            }

            // ponytail: pass 2 trusts the archive not to change between passes (a swap mid-call can still fail halfway);
            // CREATE_NEW never overwrites, and the round's checkpoint lets /undo clean up a half-done extraction.
            to.createDirectories()
            entries.forEach { e ->
                val target = to.resolve(e.name).normalize()
                if (e.isDirectory) target.createDirectories()
                else {
                    target.parent.createDirectories()
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                        z.getInputStream(e).use { copyCapped(it, out, maxFileBytes) }
                    }
                }
            }
            "Extracted ${files.size} files ($total bytes) from ${a.path} into ${a.to}/"
        }
    }
}
