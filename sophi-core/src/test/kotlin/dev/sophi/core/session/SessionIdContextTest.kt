package dev.sophi.core.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class SessionIdContextTest : FunSpec({
    test("sessionId is readable from coroutineContext when the element is present") {
        runBlocking(SessionIdContext("session-1")) {
            coroutineContext[SessionIdContext]?.sessionId shouldBe "session-1"
        }
    }

    test("sessionId propagates into a structured child coroutine") {
        runBlocking(SessionIdContext("session-2")) {
            coroutineScope {
                val childSessionId = async { coroutineContext[SessionIdContext]?.sessionId }.await()
                childSessionId shouldBe "session-2"
            }
        }
    }

    test("coroutineContext has no SessionIdContext when none was set") {
        runBlocking {
            coroutineContext[SessionIdContext].shouldBeNull()
        }
    }
})
