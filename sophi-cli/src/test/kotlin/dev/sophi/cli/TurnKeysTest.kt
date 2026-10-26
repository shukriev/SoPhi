package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TurnKeysTest : FunSpec({
    // [tail] is what next() returns once the bytes run out: -2 timeout, -1 EOF. A decoder that
    // keeps reading past the bytes plus a few tail values is looping, so fail instead of spinning.
    fun decode(vararg bytes: Int, tail: Int = -2): KeyAction = runBlocking {
        val rest = bytes.drop(1).iterator()
        var tailReads = 0
        decodeTurnKey(bytes[0]) {
            if (rest.hasNext()) rest.next() else tail.also { check(++tailReads < 5) { "decoder loops on $tail" } }
        }
    }
    fun key(k: TurnKey) = KeyAction.Key(k)

    test("printable characters") {
        decode('h'.code) shouldBe key(TurnKey.Text('h'))
        decode('T'.code) shouldBe key(TurnKey.Text('T'))
    }
    test("backspace and enter") {
        decode(127) shouldBe key(TurnKey.Backspace)
        decode(8) shouldBe key(TurnKey.Backspace)
        decode(13) shouldBe key(TurnKey.Enter)
        decode(10) shouldBe key(TurnKey.Enter)
    }
    test("escape sequences") {
        decode(27, 13) shouldBe key(TurnKey.AltEnter)
        decode(27, -2) shouldBe KeyAction.Interrupt
        decode(27, '['.code, 'A'.code) shouldBe KeyAction.Ignore
        decode(27, '['.code, '1'.code, ';'.code, '5'.code, 'C'.code) shouldBe KeyAction.Ignore
        decode(27, 'O'.code, 'P'.code) shouldBe KeyAction.Ignore
        decode(27, '['.code, '1'.code, tail = -1) shouldBe KeyAction.Ignore
    }
    test("ESC interrupts only alone, doubled or at EOF; ESC plus a byte (Option as Meta) is ignored") {
        decode(27, 27) shouldBe KeyAction.Interrupt
        decode(27, tail = -1) shouldBe KeyAction.Interrupt
        decode(27, 'b'.code) shouldBe KeyAction.Ignore
        decode(27, 'f'.code) shouldBe KeyAction.Ignore
        decode(27, 127) shouldBe KeyAction.Ignore
    }
    test("bracketed paste markers start and end a paste") {
        decode(27, '['.code, '2'.code, '0'.code, '0'.code, '~'.code) shouldBe key(TurnKey.PasteStart)
        decode(27, '['.code, '2'.code, '0'.code, '1'.code, '~'.code) shouldBe key(TurnKey.PasteEnd)
        decode(27, '['.code, '2'.code, '~'.code) shouldBe KeyAction.Ignore
    }
    test("other control bytes are ignored") {
        decode(3) shouldBe KeyAction.Ignore
        decode(1) shouldBe KeyAction.Ignore
    }
    test("ScriptedInputSource delivers typed keys to onKey and still ends on esc") {
        runBlocking {
            val input = ScriptedInputSource(emptyList())
            val got = mutableListOf<TurnKey>()
            val job = async { input.awaitControlKeys('\u0014', {}, { got += it }) }
            input.type(TurnKey.Text('a'), TurnKey.Enter)
            repeat(5) { yield() }
            input.signalEsc()
            job.await()
            got shouldBe listOf(TurnKey.Text('a'), TurnKey.Enter)
        }
    }
})
