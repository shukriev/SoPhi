package dev.sophi.core.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.concurrent.thread

class TurnInboxTest : FunSpec({
    test("blank text is ignored") {
        val inbox = TurnInbox()
        inbox.steer("  "); inbox.followUp("")
        inbox.snapshot() shouldBe emptyList()
    }

    test("drainSteers empties steers only") {
        val inbox = TurnInbox()
        inbox.steer("a"); inbox.followUp("b")
        inbox.drainSteers() shouldBe listOf("a")
        inbox.drainSteers() shouldBe emptyList()
        inbox.snapshot() shouldBe listOf(TurnInbox.Queued("b", true))
    }

    test("drainAll returns steers then follow-ups and empties both") {
        val inbox = TurnInbox()
        inbox.followUp("f1"); inbox.steer("s1"); inbox.steer("s2")
        inbox.drainAll() shouldBe listOf("s1", "s2", "f1")
        inbox.snapshot() shouldBe emptyList()
    }

    test("takeFollowUp pops in order") {
        val inbox = TurnInbox()
        inbox.followUp("f1"); inbox.followUp("f2")
        inbox.takeFollowUp() shouldBe "f1"
        inbox.takeFollowUp() shouldBe "f2"
        inbox.takeFollowUp() shouldBe null
    }

    test("concurrent steers lose none") {
        val inbox = TurnInbox()
        (1..8).map { t -> thread { repeat(1000) { inbox.steer("$t-$it") } } }.forEach { it.join() }
        inbox.drainSteers().size shouldBe 8000
    }
})
