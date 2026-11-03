package dev.sophi.core.session

import dev.sophi.core.audit.ActorContext
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.tools.SessionSearchTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SessionSearchTest : FunSpec({
    lateinit var dir: java.nio.file.Path
    lateinit var manager: FileSessionManager

    beforeTest {
        dir = createTempDirectory("sophi-search-test")
        manager = FileSessionManager(dir)
    }

    fun saved(id: String, title: String?, mtime: Long, vararg turns: Pair<EntryRole, String>) {
        val s = AgentSession(id, title = title)
        turns.forEach { (role, text) -> s.append(role, text) }
        manager.save(s)
        Files.setLastModifiedTime(dir.resolve("$id.jsonl"), FileTime.fromMillis(mtime))
    }

    test("every term, any case, newest session first, with title and a snippet") {
        saved("old", "Old chat", 1_000, EntryRole.USER to "We picked Postgres for the ledger.")
        saved("new", "New chat", 2_000, EntryRole.ASSISTANT to "Decision: POSTGRES stays, the ledger moves next week.")
        saved("other", null, 3_000, EntryRole.USER to "postgres only, nothing else")

        val hits = searchSessions(manager, "postgres Ledger")
        hits.map { it.sessionId } shouldBe listOf("new", "old")
        hits[0].title shouldBe "New chat"
        hits[0].snippet shouldContain "POSTGRES stays"
    }

    test("skips the given session, system prompts, and stops at the limit") {
        saved("a", null, 1_000, EntryRole.USER to "kiwi")
        saved("b", null, 2_000, EntryRole.USER to "kiwi")
        saved("c", null, 3_000, EntryRole.SYSTEM to "kiwi")
        searchSessions(manager, "kiwi", skip = "b").map { it.sessionId } shouldBe listOf("a")
        searchSessions(manager, "kiwi", limit = 1).map { it.sessionId } shouldBe listOf("b")
    }

    test("secrets never come back, by pattern or by the mask, and don't match either") {
        val mask = SecretMask().apply { add("hunter2hunter2") }
        saved("s", null, 1_000, EntryRole.TOOL_RESULT to "deploy key ghp_abcdefghijklmnopqrstuvwxyz0123 and password hunter2hunter2 for deploy")
        val hit = searchSessions(manager, "deploy", mask = mask).single()
        hit.snippet shouldNotContain "ghp_abc"
        hit.snippet shouldNotContain "hunter2"
        searchSessions(manager, "ghp_abcdefghijklmnopqrstuvwxyz0123", mask = mask).shouldBeEmpty()
    }

    test("unreadable lines and odd file names are skipped, not fatal") {
        saved("good", null, 1_000, EntryRole.USER to "mango")
        dir.resolve("broken.jsonl").writeText("not json\n{\"half\":")
        dir.resolve("bad.name.jsonl").writeText("")
        searchSessions(manager, "mango").map { it.sessionId } shouldBe listOf("good")
    }

    test("the tool skips the current session and refuses a non-local actor") {
        saved("now", null, 2_000, EntryRole.USER to "papaya")
        saved("before", "Earlier", 1_000, EntryRole.USER to "papaya salad")
        val tool = SessionSearchTool(manager)
        val out = runBlocking { withContext(SessionIdContext("now")) { tool.execute("""{"query":"papaya"}""") } }
        out shouldContain "before"
        out shouldContain "Earlier"
        out shouldNotContain "now"
        runBlocking { tool.execute("""{"query":"  "}""") } shouldStartWith "Error:"
        runBlocking { tool.execute("""{"query":"zzz"}""") } shouldBe "No past sessions mention: zzz"
        runBlocking { withContext(ActorContext("bob")) { tool.execute("""{"query":"papaya"}""") } } shouldStartWith "Error:"
    }
})
