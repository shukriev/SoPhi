package dev.sophi.core.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class FileSessionManagerTest : FunSpec({
    lateinit var manager: FileSessionManager
    lateinit var sessionsDir: java.nio.file.Path

    beforeTest {
        sessionsDir = createTempDirectory("sophi-session-test")
        manager = FileSessionManager(sessionsDir)
    }

    test("create() returns session with non-empty id and no entries") {
        val session = manager.create()
        session.id shouldNotBe ""
        session.entries.shouldBeEmpty()
        session.tip shouldBe null
    }

    test("create() with title preserves title on the returned session") {
        val session = manager.create(title = "My Chat")
        session.title shouldBe "My Chat"
    }

    test("save() and load() round-trip preserves entries with roles and content") {
        val session = manager.create()
        session.append(EntryRole.USER, "hello")
        session.append(EntryRole.ASSISTANT, "hi there")
        manager.save(session)

        val loaded = manager.load(session.id)
        loaded.entries shouldHaveSize 2
        loaded.entries[0].role shouldBe EntryRole.USER
        loaded.entries[0].content shouldBe "hello"
        loaded.entries[1].role shouldBe EntryRole.ASSISTANT
        loaded.entries[1].content shouldBe "hi there"
    }

    test("load() restores parentId links so branch() works correctly") {
        val session = manager.create()
        val a = session.append(EntryRole.USER, "a")
        val b = session.append(EntryRole.ASSISTANT, "b")
        manager.save(session)

        val loaded = manager.load(session.id)
        loaded.tip?.id shouldBe b.id
        val chain = loaded.branch()
        chain shouldHaveSize 2
        chain[0].id shouldBe a.id
        chain[1].id shouldBe b.id
        chain[1].parentId shouldBe a.id
    }

    test("load() for unknown session id throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { manager.load("nonexistent") }
    }

    test("list() returns one SessionMeta per saved session") {
        val s1 = manager.create()
        s1.append(EntryRole.USER, "hi")
        manager.save(s1)

        val s2 = manager.create()
        s2.append(EntryRole.USER, "hello")
        s2.append(EntryRole.ASSISTANT, "world")
        manager.save(s2)

        val metas = manager.list()
        metas shouldHaveSize 2
        val meta1 = metas.first { it.id == s1.id }
        val meta2 = metas.first { it.id == s2.id }
        meta1.entryCount shouldBe 1
        meta2.entryCount shouldBe 2
    }

    test("list() returns empty list when no sessions saved") {
        manager.list().shouldBeEmpty()
    }

    test("re-saving a session reflects its latest entries") {
        val session = manager.create()
        session.append(EntryRole.USER, "first")
        manager.save(session)

        session.append(EntryRole.ASSISTANT, "second")
        manager.save(session)

        val loaded = manager.load(session.id)
        loaded.entries shouldHaveSize 2
        loaded.entries[1].content shouldBe "second"
    }

    test("load() restores metadata on entries") {
        val session = manager.create()
        session.append(EntryRole.USER, "hi", metadata = mapOf("src" to "cli"))
        manager.save(session)

        val loaded = manager.load(session.id)
        loaded.entries[0].metadata shouldBe mapOf("src" to "cli")
    }

    test("create() with parentSessionId preserves it on the returned session") {
        val session = manager.create(parentSessionId = "parent-1")
        session.parentSessionId shouldBe "parent-1"
    }

    test("save() and load() round-trip preserves parentSessionId") {
        val session = manager.create(parentSessionId = "parent-1")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        val loaded = manager.load(session.id)
        loaded.parentSessionId shouldBe "parent-1"
    }

    test("load() returns null parentSessionId when no sidecar file exists") {
        val session = manager.create()
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.load(session.id).parentSessionId shouldBe null
    }

    test("list() includes parentSessionId for sessions that have one") {
        val session = manager.create(parentSessionId = "parent-1")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.list().first { it.id == session.id }.parentSessionId shouldBe "parent-1"
    }

    test("list() returns null parentSessionId for sessions without one") {
        val session = manager.create()
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.list().first { it.id == session.id }.parentSessionId shouldBe null
    }

    test("save() leaves only the session files behind, no temp staging files") {
        val session = manager.create(parentSessionId = "parent-1")
        session.append(EntryRole.USER, "hi")
        manager.save(session)
        manager.save(session)

        val names = sessionsDir.listDirectoryEntries().map { it.fileName.toString() }.sorted()
        names shouldBe listOf("${session.id}.jsonl", "${session.id}.meta.json")
    }

    test("load() rejects session ids that could escape the sessions directory") {
        shouldThrow<IllegalArgumentException> { manager.load("../evil") }
        shouldThrow<IllegalArgumentException> { manager.load("a/b") }
        shouldThrow<IllegalArgumentException> { manager.load("a\\b") }
        shouldThrow<IllegalArgumentException> { manager.load("") }
    }

    test("save() rejects session ids that could escape the sessions directory") {
        shouldThrow<IllegalArgumentException> { manager.save(AgentSession(id = "../evil")) }
    }

    test("load() returns null parentSessionId when sidecar file is corrupted, instead of throwing") {
        val session = manager.create(parentSessionId = "parent-1")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        sessionsDir.resolve("${session.id}.meta.json").writeText("{ not valid json ")

        manager.load(session.id).parentSessionId shouldBe null
    }

    test("list() returns null parentSessionId for a session with a corrupted sidecar, without throwing") {
        val session = manager.create(parentSessionId = "parent-1")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        sessionsDir.resolve("${session.id}.meta.json").writeText("not json at all")

        val metas = manager.list()
        metas shouldHaveSize 1
        metas.first { it.id == session.id }.parentSessionId shouldBe null
    }

    test("saveConfigSnapshot writes sidecar fields that survive a later save()") {
        val manager = FileSessionManager(sessionsDir)
        val session = manager.create()
        manager.saveConfigSnapshot(session.id, "claude-x", "be nice")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.readConfigSnapshot(session.id) shouldBe ("claude-x" to "be nice")
    }

    test("config snapshot methods reject session ids that could escape the sessions directory") {
        shouldThrow<IllegalArgumentException> { manager.readConfigSnapshot("../evil") }
        shouldThrow<IllegalArgumentException> { manager.saveConfigSnapshot("../evil", "m", null) }
    }

    test("save() persists the title set at create() time, visible after load()") {
        val session = manager.create(title = "My Chat")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.load(session.id).title shouldBe "My Chat"
    }

    test("list() includes title for sessions that have one") {
        val session = manager.create(title = "My Chat")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.list().first { it.id == session.id }.title shouldBe "My Chat"
    }

    test("list() and load() return null title for sessions without one") {
        val session = manager.create()
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.load(session.id).title shouldBe null
        manager.list().first { it.id == session.id }.title shouldBe null
    }

    test("rename() updates the title returned by load() and list()") {
        val session = manager.create(title = "Old Name")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.rename(session.id, "New Name")

        manager.load(session.id).title shouldBe "New Name"
        manager.list().first { it.id == session.id }.title shouldBe "New Name"
    }

    test("rename() rejects session ids that could escape the sessions directory") {
        shouldThrow<IllegalArgumentException> { manager.rename("../evil", "x") }
    }

    test("delete() removes both the jsonl and sidecar files") {
        val session = manager.create(title = "Doomed")
        session.append(EntryRole.USER, "hi")
        manager.save(session)

        manager.delete(session.id)

        sessionsDir.listDirectoryEntries().shouldBeEmpty()
        shouldThrow<IllegalArgumentException> { manager.load(session.id) }
    }

    test("delete() on a nonexistent session id is a no-op, does not throw") {
        manager.delete("never-existed-but-valid-id")
    }

    test("delete() rejects session ids that could escape the sessions directory") {
        shouldThrow<IllegalArgumentException> { manager.delete("../evil") }
    }

    test("saving one more entry to a 5,000-entry session appends one line and rewrites nothing") {
        val session = manager.create()
        repeat(5_000) { session.append(EntryRole.USER, "m$it") }
        manager.save(session)
        val file = sessionsDir.resolve("${session.id}.jsonl")
        val before = Files.readAllBytes(file)
        val added = session.append(EntryRole.ASSISTANT, "one more")
        manager.save(session)
        val after = Files.readAllBytes(file)
        after.copyOfRange(0, before.size).toList() shouldBe before.toList()
        String(after, before.size, after.size - before.size) shouldBe Json.encodeToString(added) + "\n"
    }

    test("a crash mid-write loses only the partial last line, and the next save still appends cleanly") {
        val session = manager.create()
        session.append(EntryRole.USER, "kept")
        manager.save(session)
        val file = sessionsDir.resolve("${session.id}.jsonl")
        Files.writeString(file, "{\"id\":\"half\",\"rol", StandardOpenOption.APPEND)
        manager.load(session.id).entries.map { it.content } shouldBe listOf("kept")
        val reloaded = FileSessionManager(sessionsDir).load(session.id)
        reloaded.append(EntryRole.ASSISTANT, "after crash")
        FileSessionManager(sessionsDir).save(reloaded)
        manager.load(session.id).entries.map { it.content } shouldBe listOf("kept", "after crash")
    }

    test("load restores the checked-out tip, not the last entry") {
        val session = manager.create()
        val first = session.append(EntryRole.USER, "a")
        session.append(EntryRole.ASSISTANT, "b")
        session.checkout(first.id)
        manager.save(session)
        manager.load(session.id).tip?.id shouldBe first.id
    }

    test("a sidecar tip naming a missing entry falls back to the last entry") {
        val session = manager.create(title = "t")
        session.append(EntryRole.USER, "a")
        val last = session.append(EntryRole.ASSISTANT, "b")
        manager.save(session)
        sessionsDir.resolve("${session.id}.meta.json").writeText("""{"title":"t","tip":"gone"}""")
        manager.load(session.id).tip?.id shouldBe last.id
    }

    test("a legacy file without a trailing newline loads and takes appends on a new line") {
        val e1 = SessionEntry("e1", null, EntryRole.USER, "old", 1L)
        val e2 = SessionEntry("e2", "e1", EntryRole.ASSISTANT, "older reply", 2L)
        sessionsDir.resolve("legacy.jsonl").writeText(Json.encodeToString(e1) + "\n" + Json.encodeToString(e2))
        val session = manager.load("legacy")
        session.tip?.id shouldBe "e2"
        session.append(EntryRole.USER, "new")
        manager.save(session)
        manager.load("legacy").entries.map { it.content } shouldBe listOf("old", "older reply", "new")
    }

    test("two copies of one session saved in turn keep both copies' entries") {
        val a = manager.create()
        a.append(EntryRole.USER, "root")
        manager.save(a)
        val b = manager.load(a.id)
        a.append(EntryRole.ASSISTANT, "from a")
        manager.save(a)
        b.append(EntryRole.ASSISTANT, "from b")
        manager.save(b)
        manager.load(a.id).entries.map { it.content } shouldBe listOf("root", "from a", "from b")
    }

    test("another manager's appends are seen before this one appends") {
        val other = FileSessionManager(sessionsDir)
        val session = manager.create()
        session.append(EntryRole.USER, "1")
        manager.save(session)
        val theirs = other.load(session.id).also { it.append(EntryRole.ASSISTANT, "2") }
        other.save(theirs)
        session.append(EntryRole.ASSISTANT, "3")
        manager.save(session)
        val lines = sessionsDir.resolve("${session.id}.jsonl").readLines().filter { it.isNotBlank() }
        lines shouldHaveSize 3
        manager.load(session.id).entries.map { it.content } shouldBe listOf("1", "2", "3")
    }

    test("a re-parented entry's new version wins on load, so a compacted branch survives") {
        val session = manager.create()
        val u = session.append(EntryRole.USER, "q")
        val r = session.append(EntryRole.ASSISTANT, "a")
        manager.save(session)
        val root = SessionEntry("sum", null, EntryRole.USER, "summary", 3L)
        val compacted = AgentSession(session.id, initialEntries = listOf(root, u, r.copy(parentId = "sum")), initialTipId = r.id)
        manager.save(compacted)
        manager.load(session.id).branch().map { it.id } shouldBe listOf("sum", r.id)
        manager.list().single { it.id == session.id }.entryCount shouldBe 3
    }
})
