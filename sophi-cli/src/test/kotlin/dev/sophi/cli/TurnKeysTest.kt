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
        TurnKeyDecoder().decode(bytes[0]) {
            if (rest.hasNext()) rest.next() else tail.also { check(++tailReads < 5) { "decoder loops on $tail" } }
        }
    }
    // One decoder over a whole byte stream, as the terminal loop drives it. SLOW is a byte that
    // arrives late: a read waiting under 100 ms times out (-2) before it, a longer one gets it.
    val SLOW = -3
    fun decodeAll(vararg bytes: Int): List<KeyAction> = runBlocking {
        val q = ArrayDeque(bytes.toList())
        val decoder = TurnKeyDecoder()
        val out = mutableListOf<KeyAction>()
        while (q.isNotEmpty()) {
            val first = q.removeFirst().takeIf { it != SLOW } ?: continue
            val action = decoder.decode(first) { t ->
                when {
                    q.isEmpty() -> -2
                    q.first() == SLOW && t < 100 -> -2
                    q.first() == SLOW -> { q.removeFirst(); q.removeFirstOrNull() ?: -2 }
                    else -> q.removeFirst()
                }
            }
            if (action != KeyAction.Ignore) out += action
        }
        out
    }
    fun bytes(text: String) = text.map { it.code }.toIntArray()
    val pasteStart = bytes("\u001b[200~")
    val pasteEnd = bytes("\u001b[201~")
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
    test("inside a bracketed paste, Enter is a newline in the text and CRLF is one newline") {
        decodeAll(*pasteStart, 'a'.code, 13, 10, 'b'.code, 13, 'c'.code, *pasteEnd, 13) shouldBe listOf(
            key(TurnKey.Text('a')), key(TurnKey.Text('\n')), key(TurnKey.Text('b')), key(TurnKey.Text('\n')),
            key(TurnKey.Text('c')), key(TurnKey.Enter)
        )
    }
    test("an ESC inside a paste never interrupts: colour codes, ESC + byte and a lone ESC are dropped") {
        decodeAll(*pasteStart, *bytes("\u001b[31mx\u001bq"), 27, SLOW, 'y'.code, 'z'.code, 27, SLOW, *pasteEnd, 13) shouldBe
            listOf(key(TurnKey.Text('x')), key(TurnKey.Text('z')), key(TurnKey.Enter))
    }
    test("a paste end marker split by a pause still ends the paste") {
        decodeAll(*pasteStart, 'a'.code, 27, SLOW, '['.code, '2'.code, '0'.code, SLOW, '1'.code, '~'.code, 13) shouldBe
            listOf(key(TurnKey.Text('a')), key(TurnKey.Enter))
    }
    test("a paste whose end marker never comes ends after a quiet second") {
        runBlocking {
            val decoder = TurnKeyDecoder()
            val start = bytes("\u001b[200~").drop(1).iterator()
            decoder.decode(27) { if (start.hasNext()) start.next() else -2 }
            repeat(PASTE_QUIET_READS - 1) { decoder.idle() }
            decoder.decode(13) { -2 } shouldBe key(TurnKey.Text('\n'))
            repeat(PASTE_QUIET_READS - 1) { decoder.idle() }
            decoder.pasting shouldBe true // a key in between restarts the count
            decoder.idle()
            decoder.decode(13) { -2 } shouldBe key(TurnKey.Enter)
        }
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
