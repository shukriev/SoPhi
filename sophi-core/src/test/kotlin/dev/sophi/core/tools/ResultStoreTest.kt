package dev.sophi.core.tools

import dev.sophi.core.session.SessionIdContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText

class ResultStoreTest : FunSpec({
    lateinit var dir: Path
    lateinit var store: ResultStore
    beforeTest { dir = tempdir().toPath(); store = ResultStore(dir, pageChars = 500) }

    fun lines(n: Int) = (1..n).joinToString("\n") { "line %04d".format(it) } // 9 chars + newline each: 500 lines are a half page then 10 pages

    fun read(s: ResultStore, id: String, page: Int? = null, session: String = "s1") = runBlocking {
        withContext(SessionIdContext(session)) {
            ResultReadTool(s).execute(if (page == null) """{"id":"$id"}""" else """{"id":"$id","page":$page}""")
        }
    }

    test("a result that fits one page is unchanged and kept nowhere") {
        val text = "x".repeat(500)
        limitResult(text, 500, store, "s1") shouldBe (text to null)
        dir.resolve("s1.results").exists() shouldBe false
    }

    test("an over-page result shows its start and end, its id and how to read more") {
        val text = lines(500) // 4,999 chars
        val (shown, id) = limitResult(text, 500, store, "s1")
        id shouldBe "r1"
        shown shouldStartWith text.take(333)
        shown.endsWith(text.takeLast(166)) shouldBe true
        shown shouldContain """[r1: 4,999 chars, 500 lines; showing the start and end; result_read("r1", 2) for the next page of 11]"""
    }

    test("result_read pages through to the end, and the pages put together are the whole result") {
        val text = lines(500)
        limitResult(text, 500, store, "s1")
        val pages = (1..11).map { read(store, "r1", it) }
        pages.last() shouldStartWith "[r1 page 11/11]\n"
        pages.joinToString("") { it.substringAfter("]\n") } shouldBe text
        read(store, "r1") shouldStartWith "[r1 page 2/11]\n" // page 1 is what the model already saw
        read(store, "r1", 12).let { isToolError(it) shouldBe true; it shouldContain "11 pages" }
    }

    test("pages end at a line break when one is near the page's end") {
        limitResult(lines(500), 500, store, "s1")
        read(store, "r1", 1).substringAfter("]\n").endsWith("\n") shouldBe true
    }

    test("an unknown id, a malformed one, or another session's id is a tool error") {
        limitResult(lines(500), 500, store, "s1")
        isToolError(read(store, "r9")) shouldBe true
        isToolError(read(store, "seven")) shouldBe true
        isToolError(read(store, "r1", session = "s2")) shouldBe true
    }

    test("the stored copy has secrets redacted, and is readable only by its owner") {
        val token = "ghp_" + "a".repeat(36)
        val text = lines(250) + "\nGITHUB_TOKEN $token\n" + lines(250)
        limitResult(text, 500, store, "s1")
        val file = dir.resolve("s1.results/r1.txt")
        file.readText() shouldNotContain token
        file.readText() shouldContain "[REDACTED:github_token]"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
    }

    test("past the session cap the oldest result is evicted, and its id says so") {
        val small = ResultStore(dir, pageChars = 500, sessionCapBytes = 12_500)
        repeat(3) { limitResult(lines(500), 500, small, "s1") } // 4,999 bytes each
        read(small, "r1", 1) shouldBe "Error: r1 was evicted; run the call again"
        read(small, "r3", 1) shouldStartWith "[r3 page 1/11]"
    }

    test("a result over the store cap keeps its head and tail and says so") {
        val big = "a".repeat(MAX_RESULT_CHARS) + "b".repeat(1_000)
        val (shown, _) = limitResult(big, 500, store, "s1")
        shown shouldContain "only the first and last 1,000,000 chars are kept"
        val kept = dir.resolve("s1.results/r1.txt").readText()
        kept.startsWith("a".repeat(1_000_000)) shouldBe true
        kept.endsWith("b".repeat(10)) shouldBe true
        (kept.length < big.length) shouldBe true
    }

    test("ids keep counting across a restart, and old ones stay readable") {
        limitResult(lines(500), 500, store, "s1")
        val again = ResultStore(dir, pageChars = 500)
        limitResult(lines(500), 500, again, "s1").second shouldBe "r2"
        read(again, "r1", 1) shouldStartWith "[r1 page 1/11]"
    }

    test("a page of 0 is floored, so paging still ends") {
        limitResult(lines(500), 0, store, "s1").second shouldBe "r1"
        read(store, "r1", 11) shouldStartWith "[r1 page 11/11]"
    }

    test("without a store an over-page result still says how much there was") {
        val (shown, id) = limitResult(lines(500), 500, null, "s1")
        id shouldBe null
        shown shouldContain "[4,999 chars, 500 lines; showing the start and end; the rest was not kept]"
    }

    test("what the model is shown plus result_read from page 2 on covers every line") {
        val text = lines(500)
        val (shown, id) = limitResult(text, 500, store, "s1")
        val seen = StringBuilder(shown)
        var page = 2
        while (true) {
            val r = read(store, id!!, page++)
            if (r.startsWith("Error:")) break
            seen.append(r)
        }
        (1..500).filter { "line %04d".format(it) !in seen } shouldBe emptyList()
    }

    test("an outside result's pages come back fenced as data, also after a restart; a local result's don't") {
        limitResult(lines(500), 500, store, "s1", outside = true)
        limitResult(lines(500), 500, store, "s1")
        read(store, "r1", 3) shouldStartWith "[r1 page 3/11]\n<tool_data source=\"r1\">"
        read(store, "r2", 3) shouldNotContain "<tool_data"
        val restarted = ResultStore(dir, pageChars = 500)
        read(restarted, "r1", 3) shouldContain "<tool_data source=\"r1\">"
        read(restarted, "r2", 3) shouldNotContain "<tool_data"
    }
})
