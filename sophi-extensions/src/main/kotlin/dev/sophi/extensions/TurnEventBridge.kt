package dev.sophi.extensions

import dev.sophi.core.agent.TurnEvent

fun PluginRegistry.turnEventBridge(sessionId: String): suspend (TurnEvent) -> Unit = { event ->
    when (event) {
        is TurnEvent.ToolCallStarted -> dispatch(
            HookPoint.BEFORE_TOOL,
            HookContext(sessionId, toolName = event.name, argumentsJson = event.argsJson)
        )
        is TurnEvent.ToolCallFinished -> dispatch(
            HookPoint.AFTER_TOOL,
            HookContext(
                sessionId, toolName = event.name, toolResult = event.result,
                success = !event.isError, durationMillis = event.durationMillis
            )
        )
        else -> Unit
    }
}
