package dev.sophi.cli

import dev.sophi.core.session.AgentSession
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select

class TuiEngine(
    private val turnController: TurnController,
    private val slashHandler: SlashHandler,
    private val input: InputSource,
    // Messages the companion sent via the hub while this CLI session was idle at the prompt.
    // Buffered (Channel.UNLIMITED at the call site) so a message sent while a turn is running
    // is not lost — it's simply picked up the next time this loop is idle here.
    private val hubMessages: ReceiveChannel<String>? = null
) {
    // Unsent text from the last turn, kept until the user's own prompt takes it: a companion
    // message winning the prompt race must not drop it.
    private var draft = ""

    private companion object {
        val EXIT_COMMANDS = setOf("exit", "quit")
    }

    suspend fun run(session: AgentSession) {
        var current = session
        while (true) {
            val line = nextLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase() in EXIT_COMMANDS) break
            current = if (trimmed.startsWith("/")) {
                slashHandler.handle(trimmed, current)
            } else {
                turnController.runTurn(current, trimmed)
            }
        }
    }

    private suspend fun nextLine(): String? {
        // Whatever the user typed during the last turn and never sent comes back at the prompt.
        draft = listOf(draft, turnController.takeDraft()).filter { it.isNotEmpty() }.joinToString("\n")
        val hub = hubMessages ?: return input.readLine(draft).also { draft = "" }
        return coroutineScope {
            val local = async { input.readLine(draft) }
            val remote = async { hub.receive() }
            select<String?> {
                local.onAwait { line -> remote.cancel(); draft = ""; line }
                remote.onAwait { text -> local.cancel(); text }
            }
        }
    }
}
