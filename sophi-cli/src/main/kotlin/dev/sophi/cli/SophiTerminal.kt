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

/**
 * A confirmation takes no answer until this long has passed with no key: someone mid-way through
 * typing a steer would otherwise approve a call with `y` or write an allow rule with `a`. Each
 * dropped key restarts the wait.
 */
const val CONFIRMATION_GRACE_MS = 500L

class SophiTerminal(
    private val jlineTerminal: Terminal,
    private val now: () -> Long = System::currentTimeMillis
) {
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

    fun readLine(prompt: String, prefill: String = ""): String? =
        try {
            reader.readLine(prompt, null as Char?, prefill)
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
    private val pendingConfirmation = AtomicReference<CompletableDeferred<ConfirmAnswer>?>(null)
    @Volatile private var alwaysOffered = false
    @Volatile private var confirmationSince = 0L

    suspend fun awaitYesNo(): Boolean = awaitConfirmation(false) == ConfirmAnswer.YES

    suspend fun awaitConfirmation(offerAlways: Boolean): ConfirmAnswer {
        val deferred = CompletableDeferred<ConfirmAnswer>()
        alwaysOffered = offerAlways
        confirmationSince = now()
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

    suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)? = null) {
        val toggleLower = toggleKey.lowercaseChar().code
        val toggleUpper = toggleKey.uppercaseChar().code
        val previousAttributes = jlineTerminal.enterRawMode()
        // Bracketed paste: the terminal wraps a paste in markers, so its newlines don't each send a steer.
        if (onKey != null) setBracketedPaste(true)
        try {
            val nonBlockingReader = jlineTerminal.reader()
            val read: suspend (Long) -> Int = { t -> withContext(Dispatchers.IO) { nonBlockingReader.read(t) } }
            val decoder = onKey?.let { TurnKeyDecoder() }
            while (currentCoroutineContext().isActive) {
                val ch = read(100)
                val confirmation = pendingConfirmation.get()
                if (decoder != null) {
                    if (ch == -2) { decoder.idle(); continue }
                    val pasted = decoder.pasting
                    val toggle = !pasted && (ch == toggleLower || ch == toggleUpper)
                    val action = if (toggle) KeyAction.Ignore else decoder.decode(ch, read)
                    if (confirmation != null) {
                        // pasted text and the paste markers never answer a confirmation; nor does the toggle key
                        val t = now()
                        if (pasted || toggle || t - confirmationSince < CONFIRMATION_GRACE_MS) { confirmationSince = t; continue }
                        answer(confirmation, action)
                        continue
                    }
                    when {
                        toggle -> onToggle()
                        action == KeyAction.Interrupt -> return
                        action is KeyAction.Key -> onKey?.invoke(action.key)
                    }
                    continue
                }
                if (confirmation != null) {
                    if (ch >= 0) {
                        val t = now()
                        if (t - confirmationSince < CONFIRMATION_GRACE_MS) { confirmationSince = t; continue }
                    }
                    when (ch) {
                        'y'.code, 'Y'.code -> confirmation.complete(ConfirmAnswer.YES)
                        'a'.code, 'A'.code -> if (alwaysOffered) confirmation.complete(ConfirmAnswer.ALWAYS)
                        'n'.code, 'N'.code, 13, 10, 27 -> confirmation.complete(ConfirmAnswer.NO)
                    }
                    continue
                }
                when (ch) {
                    toggleLower, toggleUpper -> onToggle()
                    -2 -> {}
                    27 -> return
                }
            }
        } finally {
            // A paste still arriving when the turn ends lands at the next prompt, where JLine reads it as a paste too.
            if (onKey != null) setBracketedPaste(false)
            jlineTerminal.attributes = previousAttributes
        }
    }

    private fun answer(confirmation: CompletableDeferred<ConfirmAnswer>, action: KeyAction) {
        val ch = ((action as? KeyAction.Key)?.key as? TurnKey.Text)?.ch?.lowercaseChar()
        when {
            ch == 'y' -> confirmation.complete(ConfirmAnswer.YES)
            ch == 'a' -> if (alwaysOffered) confirmation.complete(ConfirmAnswer.ALWAYS)
            ch == 'n' || action == KeyAction.Interrupt || action == KeyAction.Key(TurnKey.Enter) ->
                confirmation.complete(ConfirmAnswer.NO)
        }
    }

    private fun setBracketedPaste(on: Boolean) {
        jlineTerminal.writer().print(if (on) "\u001b[?2004h" else "\u001b[?2004l")
        jlineTerminal.writer().flush()
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
