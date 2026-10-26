package dev.sophi.cli

import dev.sophi.core.agent.TurnInbox

/** The line the user types while a turn runs: Enter steers it, Alt+Enter queues a follow-up. */
class SteerEditor(private val inbox: TurnInbox) {
    private val buffer = StringBuilder()
    private var pasting = false

    fun key(k: TurnKey) {
        when (k) {
            is TurnKey.Text -> buffer.append(k.ch)
            TurnKey.Backspace -> {
                // an emoji arrives as two chars; drop the pair together
                val n = buffer.length
                val pair = n >= 2 && buffer[n - 1].isLowSurrogate() && buffer[n - 2].isHighSurrogate()
                buffer.setLength((n - if (pair) 2 else 1).coerceAtLeast(0))
            }
            TurnKey.Enter -> if (pasting) buffer.append('\n') else send(inbox::steer)
            TurnKey.AltEnter -> send(inbox::followUp)
            TurnKey.PasteStart -> pasting = true
            TurnKey.PasteEnd -> pasting = false
        }
    }

    private fun send(to: (String) -> Unit) {
        if (buffer.isBlank()) return
        to(buffer.toString())
        buffer.clear()
    }

    fun render(): String {
        fun oneLine(text: CharSequence) = text.toString().replace("\n", " ↵ ")
        val queued = inbox.snapshot().map { if (it.followUp) "  ⏎ ${oneLine(it.text)} (next turn)" else "  ↳ ${oneLine(it.text)}" }
        val typing = if (buffer.isEmpty()) emptyList() else listOf("› ${oneLine(buffer)}")
        return (queued + typing).joinToString("\n")
    }

    /** Everything not yet sent to the model, in order, for the next prompt; leaves the editor empty. */
    fun takeDraft(): String {
        val draft = inbox.drainAll() + listOfNotNull(buffer.toString().takeIf { it.isNotBlank() })
        buffer.clear()
        return draft.joinToString("\n")
    }
}
