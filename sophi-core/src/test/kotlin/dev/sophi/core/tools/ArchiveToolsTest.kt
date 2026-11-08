package dev.sophi.core.tools

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.toStreamFlow
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.core.undo.FileRestorer
import io.kotest.core.spec.style.FunSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Writes a zip at [file]: a name ending in `/` is a directory, a ByteArray is written STORED, a String DEFLATED. */
fun zip(file: Path, vararg entries: Pair<String, Any?>): Path {
    ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (name, content) ->
            val e = ZipEntry(name)
            if (content is ByteArray) {
                e.method = ZipEntry.STORED; e.size = content.size.toLong()
                e.crc = CRC32().apply { update(content) }.value
            }
            out.putNextEntry(e)
            when (content) { is ByteArray -> out.write(content); is String -> out.write(content.toByteArray()) }
            out.closeEntry()
        }
    }
    return file
}

class ArchiveToolsTest : FunSpec({
    lateinit var root: Path

    beforeTest { root = createTempDirectory("sophi-archive").toRealPath() }

    suspend fun refused(block: suspend () -> String): String =
        runCatching { block() }.fold({ it.also { r -> r shouldContain "Error" } }, { "Error: ${it.message}" })

    /** Every path under [dir], relative, so "nothing written" is one comparison. */
    fun tree(dir: Path): Set<String> = Files.walk(dir).use { s -> s.map { dir.relativize(it).toString() }.toList().toSet() }

    test("list shows entries, declared sizes, directories, and flags an unsafe name") {
        zip(root.resolve("a.zip"), "docs/" to null, "docs/readme.txt" to "hello", "../evil.txt" to "x")
        val out = ArchiveListTool(root).execute("""{"path":"a.zip"}""")
        out shouldContain "docs/ (dir)"
        out shouldContain "docs/readme.txt  5 bytes declared"
        out shouldContain "../evil.txt"
        out shouldContain "unsafe"
        out shouldContain "3 entries"
        out.lines().single { "readme" in it } shouldNotContain "unsafe"
    }

    test("list caps the listing with a more line") {
        zip(root.resolve("big.zip"), *Array(510) { "f$it.txt" to "x" })
        val out = ArchiveListTool(root).execute("""{"path":"big.zip"}""")
        out shouldContain "… 10 more"
        out shouldContain "510 entries"
    }

    test("extract writes files and directories with their content") {
        zip(root.resolve("a.zip"), "empty/" to null, "src/main.kt" to "fun main() {}", "bin.dat" to byteArrayOf(1, 2, 3))
        val out = ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"out"}""")
        out shouldContain "Extracted 2 files"
        root.resolve("out/empty").isDirectory() shouldBe true
        root.resolve("out/src/main.kt").readText() shouldBe "fun main() {}"
        Files.readAllBytes(root.resolve("out/bin.dat")).toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    test("extract into an existing directory is fine") {
        root.resolve("out").createDirectories()
        root.resolve("out/other.txt").writeText("mine")
        zip(root.resolve("a.zip"), "new.txt" to "n")
        ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"out"}""")
        root.resolve("out/new.txt").readText() shouldBe "n"
        root.resolve("out/other.txt").readText() shouldBe "mine"
    }

    test("zip-slip: a .. entry or an absolute entry refuses the whole extraction") {
        listOf("../evil.txt", "/abs.txt", "a/../../evil.txt", "C:/win.txt", "back\\slash.txt").forEach { bad ->
            val r = createTempDirectory("sophi-archive").toRealPath()
            zip(r.resolve("a.zip"), "fine.txt" to "ok", bad to "pwned")
            val before = tree(r)
            refused { ArchiveExtractTool(r).execute("""{"path":"a.zip","to":"sub/out"}""") } shouldContain bad
            tree(r) shouldBe before
            r.parent.resolve("evil.txt").exists() shouldBe false
        }
    }

    test("a target outside the workspace is refused") {
        zip(root.resolve("a.zip"), "f.txt" to "x")
        refused { ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"../x"}""") } shouldContain "Path escapes working directory"
        refused { ArchiveListTool(root).execute("""{"path":"../a.zip"}""") } shouldContain "Path escapes working directory"
        root.parent.resolve("x").exists() shouldBe false
    }

    test("a target reached through a symlink pointing outside the workspace is refused") {
        val outside = createTempDirectory("sophi-outside")
        Files.createSymbolicLink(root.resolve("link"), outside)
        zip(root.resolve("a.zip"), "f.txt" to "x")
        refused { ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"link/deeper"}""") } shouldContain "escapes"
        outside.listDirectoryEntries() shouldBe emptyList()
    }

    test("an entry routed through a symlinked directory inside the target is refused") {
        val outside = createTempDirectory("sophi-outside")
        root.resolve("out").createDirectories()
        Files.createSymbolicLink(root.resolve("out/link"), outside)
        zip(root.resolve("a.zip"), "link/f.txt" to "x")
        refused { ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "link/f.txt"
        outside.listDirectoryEntries() shouldBe emptyList()
    }

    test("an existing target file refuses everything and stays unchanged") {
        root.resolve("out").createDirectories()
        root.resolve("out/b.txt").writeText("original")
        zip(root.resolve("a.zip"), "a.txt" to "new a", "b.txt" to "new b")
        val before = tree(root)
        refused { ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "b.txt"
        root.resolve("out/b.txt").readText() shouldBe "original"
        tree(root) shouldBe before
    }

    test("an entry over the default 10 MB per-file cap is refused by its real bytes, nothing written") {
        zip(root.resolve("a.zip"), "small.txt" to "ok", "zeros.bin" to "\u0000".repeat(10_000_001))
        val before = tree(root)
        refused { ArchiveExtractTool(root).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "zeros.bin"
        tree(root) shouldBe before
    }

    test("a stored entry is counted by streamed bytes against the cap") {
        zip(root.resolve("a.zip"), "s.bin" to ByteArray(2_000))
        refused { ArchiveExtractTool(root, maxFileBytes = 1_000).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "s.bin"
        root.resolve("out").exists() shouldBe false
    }

    test("total bytes over the cap are refused, nothing written") {
        zip(root.resolve("a.zip"), "a.txt" to "x".repeat(600), "b.txt" to "y".repeat(600))
        refused { ArchiveExtractTool(root, maxTotalBytes = 1_000).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "total"
        root.resolve("out").exists() shouldBe false
    }

    test("too many entries are refused") {
        zip(root.resolve("a.zip"), *Array(6) { "f$it.txt" to "x" })
        refused { ArchiveExtractTool(root, maxEntries = 5).execute("""{"path":"a.zip","to":"out"}""") } shouldContain "entries"
        root.resolve("out").exists() shouldBe false
    }

    test("a file that is not a zip is an error result") {
        root.resolve("notes.zip").writeText("just text")
        ArchiveListTool(root).execute("""{"path":"notes.zip"}""") shouldContain TOOL_ERROR_PREFIX
        ArchiveExtractTool(root).execute("""{"path":"notes.zip","to":"out"}""") shouldContain TOOL_ERROR_PREFIX
        root.resolve("out").exists() shouldBe false
    }

    test("undo: the round's checkpoint removes what archive_extract wrote") {
        val store = CheckpointStore(root, createTempDirectory("ckpt-home"))
        root.resolve("keep.txt").writeText("keep")
        zip(root.resolve("a.zip"), "d/x.txt" to "x", "y.txt" to "y")
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        val rounds = listOf(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "archive_extract", """{"path":"a.zip","to":"out"}""")), TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        var n = 0
        every { provider.stream(any()) } answers { rounds[(n++).coerceAtMost(1)].toStreamFlow() }
        every { sm.save(any()) } just Runs
        val registry = ToolRegistry().apply { register(ArchiveExtractTool(root)) }
        val session = AgentLoop(provider, registry, sm, contextWindowTokens = 100_000, checkpoints = store)
            .turn(AgentSession(id = "s"), "go", AgentConfig(model = "test-model", systemPrompt = "s"))
        root.resolve("out/d/x.txt").readText() shouldBe "x"

        FileRestorer(store).let { it.apply(session, it.plan(session)!!) } shouldBe emptyList()
        root.resolve("out/d/x.txt").exists() shouldBe false
        root.resolve("out/y.txt").exists() shouldBe false
        root.resolve("keep.txt").readText() shouldBe "keep"
    }

    test("risk, effects, preview and permission subjects") {
        zip(root.resolve("a.zip"), "d/" to null, "d/a.txt" to "x".repeat(2_000_000), "b.txt" to "y")
        val args = """{"path":"a.zip","to":"out"}"""
        val list = ArchiveListTool(root)
        list.riskLevel("""{"path":"a.zip"}""") shouldBe RiskLevel.SAFE
        list.effects("""{"path":"a.zip"}""") shouldBe emptySet()
        val extract = ArchiveExtractTool(root)
        extract.riskLevel(args) shouldBe RiskLevel.DESTRUCTIVE
        extract.effects(args) shouldBe setOf(EffectKind.WRITES)
        extract.confirmationPreview(args) shouldBe "Extract 2 files (2.0 MB declared) from a.zip into out/"
        extract.permissionSubjects(args)!!.map { it.text } shouldBe listOf("a.zip", "out")
        extract.ruleVerdict("""{"path":"a.zip","to":"../x"}""") shouldBe RuleVerdict.HIGH_RISK
    }
})
