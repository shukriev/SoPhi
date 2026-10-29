package dev.sophi.cli

/** Ctrl+T: the default token-view toggle, so every printable key stays typeable into a steer. */
const val DEFAULT_TOKEN_VIEW_KEY = '\u0014'

sealed interface TurnKey {
    data class Text(val ch: Char) : TurnKey
    object Backspace : TurnKey
    object Enter : TurnKey
    object AltEnter : TurnKey
    object Up : TurnKey
    object Down : TurnKey
}

sealed interface KeyAction {
    data class Key(val key: TurnKey) : KeyAction
    object Interrupt : KeyAction
    object Ignore : KeyAction
}

/** How long a lone ESC waits for a following byte before it counts as the interrupt key. */
private const val ESC_WAIT_MS = 30L
/** Consecutive empty 100 ms reads after which a paste with no end marker is treated as over. */
const val PASTE_QUIET_READS = 10
/** How long the rest of an escape sequence may lag (ssh, a large paste) before it counts as stalled. */
private const val SEQUENCE_WAIT_MS = 200L

/**
 * Decodes keypresses read in raw mode, one per [decode] call. Stateful across calls because of
 * bracketed paste: between ESC[200~ and ESC[201~ an Enter is a newline in the text and an ESC is
 * pasted noise, never the interrupt key. One decoder per turn, so a lost end marker can't outlive it.
 */
class TurnKeyDecoder {
    /** True between the paste markers. */
    var pasting = false
        private set
    private var lastWasCr = false
    private var quietReads = 0

    /**
     * The caller's read timed out ([PASTE_QUIET_READS] of these ≈ 1 s at 100 ms reads). A paste
     * arrives as a burst, so a quiet paste lost its end marker: end it, or ESC and Enter stay dead.
     */
    fun idle() {
        if (pasting && ++quietReads >= PASTE_QUIET_READS) pasting = false
    }

    /** [next] returns -2 on timeout and -1 at EOF (JLine NonBlockingReader). */
    suspend fun decode(first: Int, next: suspend (timeoutMs: Long) -> Int): KeyAction {
        quietReads = 0
        val afterCr = lastWasCr
        lastWasCr = first == 13
        return when (first) {
            127, 8 -> KeyAction.Key(TurnKey.Backspace)
            13, 10 -> when {
                !pasting -> KeyAction.Key(TurnKey.Enter)
                first == 10 && afterCr -> KeyAction.Ignore // CRLF is one newline
                else -> KeyAction.Key(TurnKey.Text('\n'))
            }
            27 -> escape(next)
            in 0..31, -2, -1 -> KeyAction.Ignore
            else -> KeyAction.Key(TurnKey.Text(first.toChar()))
        }
    }

    private suspend fun escape(next: suspend (Long) -> Int): KeyAction {
        val b = next(if (pasting) SEQUENCE_WAIT_MS else ESC_WAIT_MS)
        if (b == '['.code || b == 'O'.code) {
            // consume a CSI/SS3 sequence up to its final byte (0x40..0x7E), or until it stalls or EOF
            val seq = StringBuilder()
            var c = next(SEQUENCE_WAIT_MS)
            while (c >= 0 && c !in 0x40..0x7E) { seq.append(c.toChar()); c = next(SEQUENCE_WAIT_MS) }
            if (b == '['.code && c == '~'.code && seq.toString() == "200") pasting = true
            if (b == '['.code && c == '~'.code && seq.toString() == "201") pasting = false
            if (!pasting && seq.isEmpty() && c == 'A'.code) return KeyAction.Key(TurnKey.Up)
            if (!pasting && seq.isEmpty() && c == 'B'.code) return KeyAction.Key(TurnKey.Down)
            return KeyAction.Ignore
        }
        // pasted noise; a second ESC may start the end marker, so decode it as one
        if (pasting) return if (b == 27) escape(next) else KeyAction.Ignore
        return when (b) {
            13, 10 -> KeyAction.Key(TurnKey.AltEnter)
            // a lone ESC, ESC ESC, or EOF interrupts; ESC + byte is Option-as-Meta (Alt+b, Alt+Backspace)
            -2, -1, 27 -> KeyAction.Interrupt
            else -> KeyAction.Ignore
        }
    }
}
