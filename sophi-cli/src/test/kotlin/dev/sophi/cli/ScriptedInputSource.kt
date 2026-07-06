package dev.sophi.cli

import kotlinx.coroutines.CompletableDeferred

class ScriptedInputSource(private val lines: List<String>) : InputSource {
    private var index = 0
    private val escSignal = CompletableDeferred<Unit>()

    override suspend fun readLine(): String? =
        if (index < lines.size) lines[index++] else null

    override suspend fun awaitEsc() {
        escSignal.await()
    }

    fun signalEsc() {
        escSignal.complete(Unit)
    }
}
