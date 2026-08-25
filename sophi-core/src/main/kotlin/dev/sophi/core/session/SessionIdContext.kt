package dev.sophi.core.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the id of the session a turn is running for through structured concurrency, so
 * anything invoked during that turn — a tool call, a confirmation policy — can read which
 * session it's acting on without it being threaded through every constructor. Set once where a
 * turn is launched (`CompanionRuntime.sendMessage`, `sophi-cli`'s `TurnController`,
 * `ScheduleEngine.runTask`); read wherever it's needed (`SubagentTool`, `DecomposeGoalTool`,
 * `GuiConfirmationPolicy`).
 */
class SessionIdContext(val sessionId: String) : AbstractCoroutineContextElement(SessionIdContext) {
    companion object Key : CoroutineContext.Key<SessionIdContext>
}
