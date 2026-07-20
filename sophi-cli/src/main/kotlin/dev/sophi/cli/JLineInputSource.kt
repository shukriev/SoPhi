package dev.sophi.cli

class JLineInputSource(private val sophiTerminal: SophiTerminal) : InputSource {
    override suspend fun readLine(): String? = sophiTerminal.readLine("You: ")
    override suspend fun awaitEsc() = sophiTerminal.awaitEsc()
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) =
        sophiTerminal.awaitControlKeys(toggleKey, onToggle)
}
