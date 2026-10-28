package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.undo.FileMove
import dev.sophi.core.undo.FileRestorer
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class SlashUndoTest : FunSpec({
    val config = AgentConfig(model = "test-model")
    val output = mutableListOf<String>()
    beforeTest { output.clear() }

    fun AgentSession.round(vararg moves: FileMove) =
        append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to "[]", "moves" to Json.encodeToString(moves.toList())))

    class Fixture(val dir: Path, val session: AgentSession, val handler: SlashHandler, val original: Path, val moved: Path, val sm: FileSessionManager)

    fun fixture(vararg answers: Boolean, withRestorer: Boolean = true): Fixture {
        val dir = tempdir().toPath()
        val original = dir.resolve("a.txt")
        val moved = dir.resolve("a.txt.moved").also { it.writeText("a") }
        val sm = FileSessionManager(tempdir().toPath())
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "tidy")
        session.round(FileMove(original.toString(), moved.toString()))
        sm.save(session)
        val handler = SlashHandler(
            sm, null, config,
            fileRestorer = if (withRestorer) FileRestorer(null) else null,
            input = ScriptedInputSource(emptyList(), answers.toList())
        ) { output.add(it) }
        return Fixture(dir, session, handler, original, moved, sm)
    }

    test("/undo shows the plan and restores on yes") {
        val f = fixture(true)
        f.handler.handle("/undo", f.session)
        output.joinToString("\n") shouldContain "Restore these files?"
        output shouldContain "  restore ${f.original} (from ${f.moved})"
        output.last() shouldBe "Restored."
        f.original.exists() shouldBe true
    }

    test("/undo leaves files alone on no") {
        val f = fixture(false)
        f.handler.handle("/undo", f.session)
        output.last() shouldBe "Left as is."
        f.original.exists() shouldBe false
    }

    test("/undo with nothing to undo says so") {
        val f = fixture(true)
        f.handler.handle("/undo", f.session)
        output.clear()
        f.handler.handle("/undo", f.session)
        output shouldBe listOf("Nothing to undo.")
    }

    test("/revert without an id prints usage") {
        val f = fixture(true)
        f.handler.handle("/revert", f.session)
        output shouldBe listOf("Usage: /revert <entry-id>")
    }

    test("/revert with an unknown id prints the error") {
        val f = fixture(true)
        f.handler.handle("/revert nope", f.session)
        output shouldBe listOf("Error: Entry not found: nope")
    }

    test("undo is unavailable without a restorer") {
        val f = fixture(withRestorer = false)
        f.handler.handle("/undo", f.session)
        output shouldBe listOf("Undo is not available.")
    }

    test("plain /checkout does not touch files") {
        val f = fixture(true)
        val first = f.session.branch().first().id
        f.handler.handle("/checkout $first", f.session)
        output shouldBe listOf("Checked out entry $first")
        f.original.exists() shouldBe false
    }

    test("/checkout --files restores the rounds after that entry") {
        val f = fixture(true)
        val first = f.session.branch().first().id
        f.handler.handle("/checkout $first --files", f.session)
        output.first() shouldBe "Checked out entry $first"
        output.last() shouldBe "Restored."
        f.original.exists() shouldBe true
    }

    test("/checkout --files keeps the new tip after a resume even when the restore is declined") {
        val f = fixture(false)
        val first = f.session.branch().first().id
        f.handler.handle("/checkout $first --files", f.session)
        output.last() shouldBe "Left as is."
        f.sm.load("s1").tip?.id shouldBe first
    }
})
