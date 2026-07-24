package dev.sophi.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class ScriptedInputSource(
    private val lines: List<String>,
    private val yesNoAnswers: List<Boolean> = emptyList()
) : InputSource {
    private var index = 0
    private var yesNoIndex = 0
    private val escSignal = CompletableDeferred<Unit>()
    private val toggleSignal = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun readLine(): String? =
        if (index < lines.size) lines[index++] else null

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
