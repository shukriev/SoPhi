package dev.sophi.cli

enum class ConfirmAnswer { YES, NO, ALWAYS }

interface InputSource {
    suspend fun readLine(prefill: String = ""): String?
    suspend fun awaitEsc()

    /**
     * Watches for [toggleKey] presses (invoking [onToggle] for each one) and returns when ESC
     * is pressed. When [onKey] is set, other typed keys are decoded and delivered to it (and a lone ESC still returns). A single reader loop handles both keys so callers never run two concurrent
     * raw-mode readers against the same terminal.
     */
    suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)? = null)

    /**
     * Asks whichever reader loop currently owns the terminal (see [awaitControlKeys]) to treat
     * its next y/n/Enter/Esc keypress as a confirmation answer, rather than reading stdin
     * independently — a second, concurrent reader would race the active loop for the same
     * keystrokes and could starve forever waiting for one that the other already consumed.
     */
    suspend fun awaitYesNo(): Boolean

    /** Like [awaitYesNo], but `a` answers [ConfirmAnswer.ALWAYS] when [offerAlways] is true (and is ignored otherwise). */
    suspend fun awaitConfirmation(offerAlways: Boolean): ConfirmAnswer =
        if (awaitYesNo()) ConfirmAnswer.YES else ConfirmAnswer.NO

    /**
     * The next line the user types and ends with Enter, for ask_user; null when they press Esc. Routed through the
     * active reader loop like [awaitConfirmation], so it never races it for keystrokes.
     */
    suspend fun awaitAnswer(): String? = readLine()
}
