package dev.sophi.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import org.jline.reader.EOFError
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/** Custom parser: a line ending in `\` is treated as incomplete, so JLine keeps reading. */
private class ContinuationParser : DefaultParser() {
    override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
        if (context == Parser.ParseContext.ACCEPT_LINE && line.endsWith("\\")) {
            throw EOFError(-1, -1, "line continuation")
        }
        return super.parse(line, cursor, context)
    }
}

class SophiTerminal(private val jlineTerminal: Terminal) {
    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(jlineTerminal)
        .history(DefaultHistory())
        .parser(ContinuationParser())
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, "... ")
        // JLine's default "event expansion" (LineReaderImpl.finish()) treats `\` as an escape
        // char and silently drops a trailing `\` + newline rather than preserving it. We handle
        // backslash-continuation ourselves (see ContinuationParser + the split/join below), so
        // disable that built-in stripping to get the raw, unmodified multi-line buffer back.
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .build()

    fun readLine(prompt: String): String? =
        try {
            reader.readLine(prompt)
                ?.split("\n")
                ?.joinToString("\n") { it.removeSuffix("\\") }
        } catch (e: EndOfFileException) {
            null
        } catch (e: UserInterruptException) {
            null
        }

    /**
     * Prints a message above the current prompt line via JLine's own out-of-band message API,
     * instead of a raw write to the terminal. A message that can arrive asynchronously relative
     * to the main input loop (a fire-and-forget background job's warning, say) needs this: a
     * plain println while readLine() already has a prompt (and possibly partial user input)
     * drawn would land glued onto that same line rather than getting a fresh one.
     */
    fun printAbove(text: String) = reader.printAbove(text)

    val isInteractive: Boolean
        get() = jlineTerminal.type != Terminal.TYPE_DUMB && jlineTerminal.type != Terminal.TYPE_DUMB_COLOR

    // Only awaitControlKeys' loop may read raw bytes off the terminal while a turn is active. A
    // pending confirmation registers here instead of opening its own stdin read, which would
    // otherwise race that loop for the same keystrokes and could starve forever.
    private val pendingConfirmation = AtomicReference<CompletableDeferred<Boolean>?>(null)

    suspend fun awaitYesNo(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        check(pendingConfirmation.compareAndSet(null, deferred)) { "a confirmation is already pending" }
        try {
            return deferred.await()
        } finally {
            pendingConfirmation.set(null)
        }
    }

    suspend fun awaitEsc() {
        val previousAttributes = jlineTerminal.enterRawMode()
        try {
            val nonBlockingReader = jlineTerminal.reader()
            while (currentCoroutineContext().isActive) {
                val ch = withContext(Dispatchers.IO) { nonBlockingReader.read(100) }
                if (ch == 27) return
            }
        } finally {
            jlineTerminal.attributes = previousAttributes
        }
    }

    suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) {
        val toggleLower = toggleKey.lowercaseChar().code
        val toggleUpper = toggleKey.uppercaseChar().code
        val previousAttributes = jlineTerminal.enterRawMode()
        try {
            val nonBlockingReader = jlineTerminal.reader()
            while (currentCoroutineContext().isActive) {
                val ch = withContext(Dispatchers.IO) { nonBlockingReader.read(100) }
                val confirmation = pendingConfirmation.get()
                if (confirmation != null) {
                    when (ch) {
                        'y'.code, 'Y'.code -> confirmation.complete(true)
                        'n'.code, 'N'.code, 13, 10, 27 -> confirmation.complete(false)
                    }
                    continue
                }
                when (ch) {
                    27 -> return
                    toggleLower, toggleUpper -> onToggle()
                }
            }
        } finally {
            jlineTerminal.attributes = previousAttributes
        }
    }

    fun close() = jlineTerminal.close()

    companion object {
        fun create(): SophiTerminal =
            // graphemeCluster(false): skip JLine's terminal capability auto-probe (a DA1-style
            // query sent to detect Unicode grapheme-cluster width support). Some terminals
            // (observed: iTerm2) reply after JLine's probe has already given up, so the reply
            // bytes leak into the first prompt as literal text. We don't need grapheme-cluster
            // detection for plain streamed chat text, so skipping the probe avoids the race.
            SophiTerminal(TerminalBuilder.builder().system(true).graphemeCluster(false).build())
    }
}
