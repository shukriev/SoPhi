package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
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

    fun store(root: Path) = CheckpointStore(root, createTempDirectory("ckpt-home"), home = createTempDirectory("home"))

    test("with checkpoints, undo keeps a file the user wrote where a deleted one was") {
        val root = createTempDirectory("restore-root")
        val trashed = createTempDirectory("trash").resolve("doc")
        root.resolve("doc").writeText("old doc")
        val checkpoints = store(root)
        val c = checkpoints.snapshot()!!
        Files.move(root.resolve("doc"), trashed)
        val session = AgentSession(id = "s")
        session.append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to "[]", "checkpoint" to c,
            "moves" to Json.encodeToString(listOf(FileMove(root.resolve("doc").toString(), trashed.toString())))))
        root.resolve("doc").writeText("new doc")

        val skipped = FileRestorer(checkpoints).let { it.apply(session, it.plan(session)!!) }

        skipped shouldHaveSize 1
        root.resolve("doc").readText() shouldBe "new doc"
        trashed.readText() shouldBe "old doc"
    }

    test("the marker records the pre-restore snapshot, and restoring to it redoes the change") {
        val root = createTempDirectory("restore-root")
        root.resolve("a.txt").writeText("a")
        val checkpoints = store(root)
        val session = AgentSession(id = "s")
        session.append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to "[]", "checkpoint" to checkpoints.snapshot()!!))
        root.resolve("a.txt").writeText("edited")

        FileRestorer(checkpoints).let { it.apply(session, it.plan(session)!!) }

        root.resolve("a.txt").readText() shouldBe "a"
        checkpoints.restore(session.branch().last().metadata["restoredFrom"]!!)
        root.resolve("a.txt").readText() shouldBe "edited"
    }

    test("the plan warns about a nested repo") {
        val root = createTempDirectory("restore-root")
        val nested = root.resolve("lib").createDirectories()
        nested.resolve("f").writeText("f")
        listOf(listOf("init", "-q"), listOf("add", "-A"), listOf("-c", "user.name=u", "-c", "user.email=u@x", "commit", "-q", "-m", "i"))
            .forEach { ProcessBuilder(listOf("git") + it).directory(nested.toFile()).start().waitFor() shouldBe 0 }
        val checkpoints = store(root)
        val session = AgentSession(id = "s")
        session.append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to "[]", "checkpoint" to checkpoints.snapshot()!!))

        FileRestorer(checkpoints).plan(session)!!.warnings shouldContain "changes inside nested repo lib"
    }

    test("nothing to undo returns null") {
        val session = AgentSession(id = "s")
        FileRestorer(null).plan(session).shouldBeNull()
        session.append(EntryRole.USER, "hi")
        session.append(EntryRole.ASSISTANT, "hello")
        FileRestorer(null).plan(session).shouldBeNull()
    }
})
