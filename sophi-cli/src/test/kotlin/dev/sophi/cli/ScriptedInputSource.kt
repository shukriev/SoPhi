package dev.sophi.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class ScriptedInputSource(
    private val lines: List<String>,
    private val yesNoAnswers: List<Boolean> = emptyList(),
    private val confirmAnswers: List<ConfirmAnswer> = emptyList(),
    /** Lines `awaitAnswer` returns in order (null = Esc); once spent it waits forever, like nobody answering. */
    private val answers: List<String?> = emptyList(),
    private val answerDelayMs: Long = 0,
) : InputSource {
    private var answerIndex = 0

    override suspend fun awaitAnswer(): String? {
        if (answerIndex >= answers.size) kotlinx.coroutines.awaitCancellation()
        kotlinx.coroutines.delay(answerDelayMs)
        return answers[answerIndex++]
    }

    private var index = 0
    private var yesNoIndex = 0
    private val escSignal = CompletableDeferred<Unit>()
    private val toggleSignal = Channel<Unit>(Channel.UNLIMITED)

    val prefills = mutableListOf<String>()
    private val keySignal = Channel<TurnKey>(Channel.UNLIMITED)

    override suspend fun readLine(prefill: String): String? {
        prefills += prefill
        return if (index < lines.size) lines[index++] else null
    }

    val offers = mutableListOf<Boolean>()
    private var confirmIndex = 0

    override suspend fun awaitConfirmation(offerAlways: Boolean): ConfirmAnswer {
        offers += offerAlways
        return confirmAnswers.getOrNull(confirmIndex++) ?: super.awaitConfirmation(offerAlways)
    }

    override suspend fun awaitYesNo(): Boolean = yesNoAnswers.getOrElse(yesNoIndex++) { false }

    override suspend fun awaitEsc() {
        escSignal.await()
    }

    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)?) {
        loop@ while (true) {
            select<Boolean> {
                escSignal.onAwait { false }
                toggleSignal.onReceive { onToggle(); true }
                keySignal.onReceive { onKey?.invoke(it); true }
            } || break@loop
        }
    }

    fun signalEsc() {
        escSignal.complete(Unit)
    }

    fun type(vararg keys: TurnKey) {
        keys.forEach { keySignal.trySend(it) }
    }

    fun signalToggle() {
        toggleSignal.trySend(Unit)
    }
}
