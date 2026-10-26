package dev.sophi.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class ScriptedInputSource(
    private val lines: List<String>,
    private val yesNoAnswers: List<Boolean> = emptyList(),
    private val confirmAnswers: List<ConfirmAnswer> = emptyList()
) : InputSource {
    private var index = 0
    private var yesNoIndex = 0
    private val escSignal = CompletableDeferred<Unit>()
    private val toggleSignal = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun readLine(): String? =
        if (index < lines.size) lines[index++] else null

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

    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) {
        loop@ while (true) {
            select<Boolean> {
                escSignal.onAwait { false }
                toggleSignal.onReceive { onToggle(); true }
            } || break@loop
        }
    }

    fun signalEsc() {
        escSignal.complete(Unit)
    }

    fun signalToggle() {
        toggleSignal.trySend(Unit)
    }
}
