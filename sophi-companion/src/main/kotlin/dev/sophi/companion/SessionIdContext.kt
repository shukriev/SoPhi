package dev.sophi.companion

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class SessionIdContext(val sessionId: String) : AbstractCoroutineContextElement(SessionIdContext) {
    companion object Key : CoroutineContext.Key<SessionIdContext>
}
