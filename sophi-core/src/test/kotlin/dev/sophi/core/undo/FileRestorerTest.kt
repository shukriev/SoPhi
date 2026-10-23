package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun AgentSession.round(vararg moves: FileMove): SessionEntry =
    append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to "[]", "moves" to Json.encodeToString(moves.toList())))

class FileRestorerTest : FunSpec({
    fun movedFile(dir: Path, name: String): FileMove {
        dir.resolve("$name.moved").writeText(name)
        return FileMove(dir.resolve(name).toString(), dir.resolve("$name.moved").toString())
    }

    test("repeated undo walks back one round at a time") {
        val dir = createTempDirectory("restore")
        val session = AgentSession(id = "s")
        val r1 = session.round(movedFile(dir, "one"))
        val r2 = session.round(movedFile(dir, "two"))
        val restorer = FileRestorer(null)

        restorer.plan(session)!!.targetEntryId shouldBe r2.id
        restorer.apply(session, restorer.plan(session)!!)

        restorer.plan(session)!!.targetEntryId shouldBe r1.id
        session.branch().last().metadata["restoredTo"] shouldBe r2.id
    }

    test("revert undoes every round from the entry on, newest move first") {
        val session = AgentSession(id = "s")
        session.round(FileMove("/a", "/b"))
        val r2 = session.round(FileMove("/c", "/d"), FileMove("/e", "/f"))
        session.append(EntryRole.USER, "next")
        session.round(FileMove("/g", "/h"), FileMove("/i", "/j"))

        val plan = FileRestorer(null).plan(session, r2.id)!!

        plan.targetEntryId shouldBe r2.id
        plan.moves shouldBe listOf(FileMove("/i", "/j"), FileMove("/g", "/h"), FileMove("/e", "/f"), FileMove("/c", "/d"))
        shouldThrow<IllegalArgumentException> { FileRestorer(null).plan(session, "nope") }
    }

    test("undo skips a move whose source path is taken again") {
        val dir = createTempDirectory("restore")
        val move = movedFile(dir, "doc")
        Path.of(move.from).writeText("new doc")
        val session = AgentSession(id = "s")
        session.round(move)

        val skipped = FileRestorer(null).let { it.apply(session, it.plan(session)!!) }

        skipped shouldHaveSize 1
        skipped[0] shouldStartWith "Skipped"
        Path.of(move.from).readText() shouldBe "new doc"
        Path.of(move.to).readText() shouldBe "doc"
    }

    test("undo without checkpoints still reverses moves") {
        val dir = createTempDirectory("restore")
        val move = FileMove(dir.resolve("sub/deep/file.txt").toString(), dir.resolve("file.txt").toString())
        Path.of(move.to).writeText("data")
        val session = AgentSession(id = "s")
        session.round(move)

        val skipped = FileRestorer(null).let { it.apply(session, it.plan(session)!!) }

        skipped shouldBe emptyList()
        Path.of(move.from).readText() shouldBe "data"
        Path.of(move.to).exists() shouldBe false
        FileRestorer(null).plan(session).shouldBeNull()
    }

    test("a marker whose target is on another branch is ignored") {
        val session = AgentSession(id = "s")
        val r1 = session.round(FileMove("/a", "/b"))
        val other = session.round(FileMove("/c", "/d"))
        session.checkout(r1.id)
        val r3 = session.round(FileMove("/e", "/f"))
        session.append(EntryRole.ASSISTANT, "restored", mapOf("replay" to "false", "restoredTo" to other.id))

        val plan = FileRestorer(null).plan(session)!!

        plan.targetEntryId shouldBe r3.id
    }

    test("nothing to undo returns null") {
        val session = AgentSession(id = "s")
        FileRestorer(null).plan(session).shouldBeNull()
        session.append(EntryRole.USER, "hi")
        session.append(EntryRole.ASSISTANT, "hello")
        FileRestorer(null).plan(session).shouldBeNull()
    }
})
