package dev.sophi.core.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AgentSessionTest : FunSpec({
    test("new session has no entries and null tip") {
        val session = AgentSession(id = "s1")
        session.entries.shouldBeEmpty()
        session.tip shouldBe null
    }

    test("append creates entry with correct role and content") {
        val session = AgentSession(id = "s1")
        val entry = session.append(EntryRole.USER, "hello")
        entry.role shouldBe EntryRole.USER
        entry.content shouldBe "hello"
        entry.parentId shouldBe null
    }

    test("second append sets parentId to first entry's id") {
        val session = AgentSession(id = "s1")
        val first = session.append(EntryRole.USER, "hello")
        val second = session.append(EntryRole.ASSISTANT, "hi")
        second.parentId shouldBe first.id
    }

    test("tip points to last appended entry") {
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "hello")
        val second = session.append(EntryRole.ASSISTANT, "hi")
        session.tip shouldBe second
    }

    test("branch() returns linear chain from root to tip") {
        val session = AgentSession(id = "s1")
        val a = session.append(EntryRole.SYSTEM, "sys")
        val b = session.append(EntryRole.USER, "hello")
        val c = session.append(EntryRole.ASSISTANT, "hi")
        session.branch() shouldBe listOf(a, b, c)
    }

    test("branch() returns empty list when no entries") {
        AgentSession(id = "s1").branch().shouldBeEmpty()
    }

    test("checkout switches the active tip") {
        val session = AgentSession(id = "s1")
        val a = session.append(EntryRole.SYSTEM, "sys")
        val b = session.append(EntryRole.USER, "hello")
        session.append(EntryRole.ASSISTANT, "hi")  // c — will branch away
        session.checkout(b.id)
        session.tip shouldBe b
    }

    test("branch() after checkout returns only the chain to checked-out entry") {
        val session = AgentSession(id = "s1")
        val a = session.append(EntryRole.SYSTEM, "sys")
        val b = session.append(EntryRole.USER, "hello")
        session.append(EntryRole.ASSISTANT, "hi")  // c
        session.checkout(b.id)
        session.branch() shouldBe listOf(a, b)
    }

    test("append after checkout branches from that point, not from c") {
        val session = AgentSession(id = "s1")
        val a = session.append(EntryRole.SYSTEM, "sys")
        val b = session.append(EntryRole.USER, "hello")
        session.append(EntryRole.ASSISTANT, "hi")  // c — original branch
        session.checkout(b.id)
        val d = session.append(EntryRole.USER, "different question")
        d.parentId shouldBe b.id
        session.branch() shouldBe listOf(a, b, d)
        session.entries shouldHaveSize 4  // a, b, c, d all retained
    }

    test("checkout throws IllegalArgumentException for unknown entry id") {
        val session = AgentSession(id = "s1")
        shouldThrow<IllegalArgumentException> { session.checkout("nonexistent") }
    }

    test("append stores metadata") {
        val session = AgentSession(id = "s1")
        val entry = session.append(EntryRole.USER, "hi", metadata = mapOf("src" to "cli"))
        entry.metadata shouldBe mapOf("src" to "cli")
    }

    test("initialEntries restores session state with tip on last entry") {
        val entries = listOf(
            SessionEntry("e1", null, EntryRole.USER, "hello", 1000L),
            SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hi", 2000L)
        )
        val session = AgentSession(id = "s1", initialEntries = entries)
        session.tip?.id shouldBe "e2"
        session.branch() shouldBe entries
    }

    test("initialTipId overrides default tip selection") {
        val entries = listOf(
            SessionEntry("e1", null, EntryRole.USER, "hello", 1000L),
            SessionEntry("e2", "e1", EntryRole.ASSISTANT, "hi", 2000L)
        )
        val session = AgentSession(id = "s1", initialEntries = entries, initialTipId = "e1")
        session.tip?.id shouldBe "e1"
    }

    test("entries are immutable snapshot — modification does not affect session") {
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "hello")
        val snapshot = session.entries
        session.append(EntryRole.ASSISTANT, "hi")
        snapshot shouldHaveSize 1  // snapshot not affected by subsequent append
    }
})
