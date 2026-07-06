package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
})
