package dev.sophi.cli

/** Ctrl+T: the default token-view toggle, so every printable key stays typeable into a steer. */
const val DEFAULT_TOKEN_VIEW_KEY = '\u0014'

sealed interface TurnKey {
    data class Text(val ch: Char) : TurnKey
    object Backspace : TurnKey
    object Enter : TurnKey
    object AltEnter : TurnKey
    /** Bracketed-paste markers: an Enter between them is a newline in the text, not a send. */
    object PasteStart : TurnKey
    object PasteEnd : TurnKey
}

sealed interface KeyAction {
    data class Key(val key: TurnKey) : KeyAction
    object Interrupt : KeyAction
    object Ignore : KeyAction
}

/** Decodes one keypress read in raw mode. [next] returns -2 on timeout (JLine NonBlockingReader). */
suspend fun decodeTurnKey(first: Int, next: suspend (timeoutMs: Long) -> Int): KeyAction = when (first) {
    127, 8 -> KeyAction.Key(TurnKey.Backspace)
    13, 10 -> KeyAction.Key(TurnKey.Enter)
    27 -> when (val b = next(30)) {
        13, 10 -> KeyAction.Key(TurnKey.AltEnter)
        '['.code, 'O'.code -> {
            // consume a CSI/SS3 sequence up to its final byte (0x40..0x7E), or until it stalls or EOF
            val seq = StringBuilder()
            var c = next(30)
            while (c >= 0 && c !in 0x40..0x7E) { seq.append(c.toChar()); c = next(30) }
            when {
                b == '['.code && c == '~'.code && seq.toString() == "200" -> KeyAction.Key(TurnKey.PasteStart)
                b == '['.code && c == '~'.code && seq.toString() == "201" -> KeyAction.Key(TurnKey.PasteEnd)
                else -> KeyAction.Ignore
            }
        }
        // a lone ESC, ESC ESC, or EOF interrupts; ESC + byte is Option-as-Meta (Alt+b, Alt+Backspace)
        -2, -1, 27 -> KeyAction.Interrupt
        else -> KeyAction.Ignore
    }
    in 0..31, -2, -1 -> KeyAction.Ignore
    else -> KeyAction.Key(TurnKey.Text(first.toChar()))
}
