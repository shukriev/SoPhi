package dev.sophi.cli

class JLineInputSource(private val sophiTerminal: SophiTerminal) : InputSource {
    override suspend fun readLine(): String? = sophiTerminal.readLine("You: ")
    override suspend fun awaitEsc() = sophiTerminal.awaitEsc()
}
