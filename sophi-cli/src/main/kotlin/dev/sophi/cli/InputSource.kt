package dev.sophi.cli

interface InputSource {
    suspend fun readLine(): String?
    suspend fun awaitEsc()

    /**
     * Watches for [toggleKey] presses (invoking [onToggle] for each one) and returns when ESC
     * is pressed. A single reader loop handles both keys so callers never run two concurrent
     * raw-mode readers against the same terminal.
     */
    suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit)
}
