package dev.sophi.cli

class JLineInputSource(private val sophiTerminal: SophiTerminal) : InputSource {
    override suspend fun readLine(prefill: String): String? = sophiTerminal.readLine("You: ", prefill)
    override suspend fun awaitEsc() = sophiTerminal.awaitEsc()
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)?) =
        sophiTerminal.awaitControlKeys(toggleKey, onToggle, onKey)
    override suspend fun awaitYesNo(): Boolean = sophiTerminal.awaitYesNo()
    override suspend fun awaitConfirmation(offerAlways: Boolean): ConfirmAnswer = sophiTerminal.awaitConfirmation(offerAlways)
}
