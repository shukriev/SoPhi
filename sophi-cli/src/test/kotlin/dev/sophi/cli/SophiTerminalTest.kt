package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SophiTerminalTest : FunSpec({
    // Built directly via the DumbTerminal(InputStream, OutputStream) constructor rather than
    // TerminalBuilder: with custom (non-system) streams, TerminalBuilder's provider negotiation
    // (ffm/jni/exec) ignores the `.dumb(true)` hint and picks a provider based on env ($TERM etc),
    // which is both non-deterministic and not actually a dumb terminal. DumbTerminal's own
    // constructor always yields a genuine Terminal.TYPE_DUMB terminal wired to the given streams.
    fun terminalWithInput(input: String): Terminal =
        DumbTerminal(ByteArrayInputStream(input.toByteArray()), ByteArrayOutputStream())

    test("readLine() returns a single submitted line") {
        val sophi = SophiTerminal(terminalWithInput("hello\n"))
        sophi.readLine("> ") shouldBe "hello"
    }

    test("readLine() joins a backslash-continued line with a real newline, stripping the backslash") {
        val sophi = SophiTerminal(terminalWithInput("first\\\nsecond\n"))
        sophi.readLine("> ") shouldBe "first\nsecond"
    }

    test("readLine() returns null at end of input") {
        val sophi = SophiTerminal(terminalWithInput(""))
        sophi.readLine("> ") shouldBe null
    }

    test("isInteractive is false for a dumb terminal") {
        val sophi = SophiTerminal(terminalWithInput("anything\n"))
        sophi.isInteractive shouldBe false
    }

    // A confirmation prompt must never open its own, independent read of the terminal: that would
    // race the awaitControlKeys loop (already active for the whole turn) for the same keystrokes
    // and could starve one of them forever. Instead it registers interest via awaitYesNo() and the
    // one active reader loop resolves it — proven here by feeding a single 'y' byte and asserting
    // the SAME awaitControlKeys() call (not a second reader) is what delivers the answer.
    test("awaitYesNo() is resolved by the concurrently-running awaitControlKeys loop reading 'y'") {
        val sophi = SophiTerminal(terminalWithInput("y"))
        val controlKeysJob = async { sophi.awaitControlKeys('T') {} }
        val answer = sophi.awaitYesNo()
        answer shouldBe true
        controlKeysJob.cancel()
    }

    test("awaitYesNo() resolves false when the awaitControlKeys loop reads 'n'") {
        val sophi = SophiTerminal(terminalWithInput("n"))
        val controlKeysJob = async { sophi.awaitControlKeys('T') {} }
        val answer = sophi.awaitYesNo()
        answer shouldBe false
        controlKeysJob.cancel()
    }

    test("awaitYesNo() does not consume a byte that doesn't answer it, and doesn't trigger the toggle") {
        var toggled = false
        val sophi = SophiTerminal(terminalWithInput("qy"))
        val controlKeysJob = async { sophi.awaitControlKeys('T') { toggled = true } }
        val answer = sophi.awaitYesNo()
        answer shouldBe true
        toggled shouldBe false
        controlKeysJob.cancel()
    }

    // printAbove routes through JLine's own out-of-band message API rather than a raw println,
    // so a message arriving asynchronously (a fire-and-forget background job's warning, say)
    // doesn't get glued onto whatever the active readLine() prompt already rendered.
    test("printAbove() writes the given text to the terminal") {
        val output = ByteArrayOutputStream()
        val sophi = SophiTerminal(DumbTerminal(ByteArrayInputStream(ByteArray(0)), output))

        sophi.printAbove("memory: encoder returned output that didn't match the expected schema")

        output.toString() shouldContain "memory: encoder returned output that didn't match the expected schema"
    }
})
