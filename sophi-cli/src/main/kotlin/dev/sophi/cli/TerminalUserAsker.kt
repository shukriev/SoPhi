package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.tools.Answer
import dev.sophi.core.tools.UserAsker
import dev.sophi.core.tools.UserQuestion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** ask_user in the terminal: numbered options and a typed reply, Esc to skip. */
class TerminalUserAsker(private val terminal: Terminal, private val input: InputSource) : UserAsker {
    // Calls of one round run in parallel; two prompts must not interleave.
    private val oneAtATime = Mutex()

    override suspend fun ask(question: UserQuestion, timeoutMs: Long?): Answer = oneAtATime.withLock {
        if (timeoutMs == null) prompt(question) else withTimeoutOrNull(timeoutMs) { prompt(question) } ?: Answer.TimedOut
    }

    private suspend fun prompt(q: UserQuestion): Answer {
        terminal.println(TextColors.cyan("Sophi asks: ${q.text}"))
        q.choices.forEachIndexed { i, c -> terminal.println("  ${i + 1}. ${c.label}${c.description?.let { " — $it" } ?: ""}") }
        repeat(2) { attempt ->
            val hint = when {
                q.choices.isEmpty() -> "Type a reply; Esc to skip: "
                q.allowFreeText -> "Answer with a number or type a reply; Esc to skip: "
                else -> "Answer with a number; Esc to skip: "
            }
            terminal.print(TextColors.cyan(hint))
            val line = input.awaitAnswer()?.trim() ?: return Answer.Dismissed
            line.toIntOrNull()?.let { n -> q.choices.getOrNull(n - 1)?.let { return Answer.Reply(it, null) } }
            if (q.allowFreeText && line.isNotEmpty()) return Answer.Reply(null, line)
            if (attempt == 0) terminal.println(TextColors.yellow("Pick 1-${q.choices.size}."))
        }
        return Answer.Dismissed
    }
}
