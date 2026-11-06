package dev.sophi.core.agent

import dev.sophi.core.agent.BackgroundSubagents.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.coroutines.EmptyCoroutineContext

class BackgroundSubagentsTest : FunSpec({

    fun BackgroundSubagents.go(session: String = "s", run: suspend (dev.sophi.core.tools.DenialLimit) -> String) =
        start(session, "explore", "look around", "sub-1", EmptyCoroutineContext, run)

    test("start returns at once while running") {
        BackgroundSubagents().use { bg ->
            val gate = CompletableDeferred<String>()
            bg.go { gate.await() }.status shouldBe Status.RUNNING
            gate.complete("x")
        }
    }

    test("result waits and returns the answer") {
        BackgroundSubagents().use { bg ->
            val gate = CompletableDeferred<String>()
            val id = bg.go { gate.await() }.id
            bg.result("s", id)?.status shouldBe Status.RUNNING
            gate.complete("answer")
            val info = bg.result("s", id, 5)!!
            info.status shouldBe Status.DONE
            info.text shouldBe "answer"
        }
    }

    test("cancel stops it") {
        BackgroundSubagents().use { bg ->
            val id = bg.go { awaitCancellation() }.id
            bg.cancel("s", id)?.status shouldBe Status.CANCELLED
            bg.result("s", id, 5)?.status shouldBe Status.CANCELLED
        }
    }

    test("a 4th concurrent start is refused") {
        BackgroundSubagents().use { bg ->
            repeat(3) { bg.go { awaitCancellation() } }
            shouldThrow<IllegalStateException> { bg.go { "x" } }.message!!.let {
                it shouldContain "b1"; it shouldContain "b2"; it shouldContain "b3"
            }
        }
    }

    test("close cancels running and saves") {
        val bg = BackgroundSubagents()
        var saved = false
        val id = bg.go { try { awaitCancellation() } finally { saved = true } }.id
        bg.close()
        saved shouldBe true
        bg.list().single { it.id == id }.status shouldBe Status.CANCELLED
        shouldThrow<IllegalStateException> { bg.go { "x" } }
    }

    test("a tripped denial limit is FAILED with its reason") {
        BackgroundSubagents().use { bg ->
            val id = bg.go { limit -> repeat(3) { limit.denied("rm") }; awaitCancellation() }.id
            val info = bg.result("s", id, 5)!!
            info.status shouldBe Status.FAILED
            info.text!! shouldContain "Stopped after 3 blocked actions"
        }
    }

    test("another session can't see it, the host can") {
        BackgroundSubagents().use { bg ->
            val id = bg.go { "x" }.id
            bg.result("other", id).shouldBeNull()
            bg.cancel("other", id).shouldBeNull()
            bg.list("other") shouldHaveSize 0
            bg.list(null).map { it.id } shouldBe listOf(id)
            bg.result(null, id, 5)?.status shouldBe Status.DONE
        }
    }

    test("takeFinished returns each result once") {
        BackgroundSubagents().use { bg ->
            val id = bg.go { "x" }.id
            bg.result(null, id, 5) // the host reading it doesn't announce it to the model
            bg.takeFinished("other") shouldHaveSize 0
            bg.takeFinished("s").map { it.id } shouldBe listOf(id)
            bg.takeFinished("s") shouldHaveSize 0
        }
    }

    test("a result the model read is not announced again") {
        BackgroundSubagents().use { bg ->
            val id = bg.go { "x" }.id
            bg.result("s", id, 5)?.status shouldBe Status.DONE
            bg.takeFinished("s") shouldHaveSize 0
        }
    }

    test("onChange fires on start and end") {
        BackgroundSubagents().use { bg ->
            val seen = mutableListOf<Status>()
            val ended = CompletableDeferred<Unit>()
            bg.onChange = { synchronized(seen) { seen += it.status }; if (it.status != Status.RUNNING) ended.complete(Unit) }
            bg.go { "x" }
            ended.await()
            seen shouldBe listOf(Status.RUNNING, Status.DONE)
        }
    }

    test("a throwing onChange doesn't break the run") {
        BackgroundSubagents().use { bg ->
            bg.onChange = { error("boom") }
            val id = bg.go { "x" }.id
            bg.result("s", id, 5)?.status shouldBe Status.DONE
        }
    }
})
