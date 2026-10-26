package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TurnKeysTest : FunSpec({
    fun decode(vararg bytes: Int): KeyAction = runBlocking {
        val rest = bytes.drop(1).iterator()
        decodeTurnKey(bytes[0]) { if (rest.hasNext()) rest.next() else -2 }
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
