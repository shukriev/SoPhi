package dev.sophi.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    val isInteractive: Boolean
        get() = jlineTerminal.type != Terminal.TYPE_DUMB && jlineTerminal.type != Terminal.TYPE_DUMB_COLOR

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
