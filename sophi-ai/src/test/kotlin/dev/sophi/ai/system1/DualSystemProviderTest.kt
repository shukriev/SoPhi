package dev.sophi.ai.system1

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class DualSystemProviderTest : FunSpec({
    fun text(s: String) = LLMResponse.Text(s, TokenUsage(3, 2))
    fun req(judgment: String? = Judgments.ENCODER) = CompletionRequest(
        messages = listOf(Message(MessageRole.USER, "hi")), model = "big", maxTokens = 1024,
        temperature = 0.0, judgment = judgment
    )

    class Rig(mode: System1Mode, rate: Double = 1.0, sink: (suspend (Decision) -> Unit)? = null, random: Random = Random(1)) {
        val s2 = mockk<LLMProvider>().also { every { it.name } returns "s2" }
        val s1 = mockk<LLMProvider>()
        val rows = CopyOnWriteArrayList<Decision>()
        val warnings = CopyOnWriteArrayList<String>()
        // Unconfined: the background shadow runs until its first real suspension, so tests stay deterministic.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = DualSystemProvider(
            s2, s1,
            System1Settings("http://s1", "small", modes = mapOf(Judgments.ENCODER to mode),
                shadowSampleRate = mapOf(Judgments.ENCODER to rate)),
            sink = sink ?: { rows += it }, onWarning = { warnings += it }, scope = scope, random = random
        )
        suspend fun drain() = scope.coroutineContext.job.children.toList().joinAll()
    }

    test("an untagged request goes to S2 only and writes no row") {
        val rig = Rig(System1Mode.SHADOW)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req(judgment = null)) } shouldBe text("s2")
        coVerify(exactly = 0) { rig.s1.complete(any()) }
        rig.rows.size shouldBe 0
    }

    test("stream() always goes to S2") {
        val rig = Rig(System1Mode.SYSTEM1)
        every { rig.s2.stream(any()) } returns emptyFlow()
        rig.provider.stream(req())
        verify(exactly = 1) { rig.s2.stream(any()) }
        verify(exactly = 0) { rig.s1.stream(any()) }
    }

    test("name is the S2 provider's name") {
        Rig(System1Mode.SYSTEM2).provider.name shouldBe "s2"
    }

    test("SYSTEM2: S2 answers and the row has no s1") {
        val rig = Rig(System1Mode.SYSTEM2)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()) } shouldBe text("s2")
        coVerify(exactly = 0) { rig.s1.complete(any()) }
        val row = rig.rows.single()
        row.mode shouldBe System1Mode.SYSTEM2
        row.served shouldBe "s2"
        row.s1 shouldBe null
        row.s2!!.text shouldBe "s2"
        row.s2!!.usage shouldBe DecisionUsage(3, 2)
        row.request.messages.single() shouldBe DecisionMessage("USER", "hi")
    }

    test("SHADOW: the caller gets S2; S1 runs after it with the small model and no reasoning") {
        val rig = Rig(System1Mode.SHADOW)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        coEvery { rig.s1.complete(any()) } returns text("s1")
        runBlocking { rig.provider.complete(req()) shouldBe text("s2"); rig.drain() }
        coVerifyOrder {
            rig.s2.complete(match { it.model == "big" })
            rig.s1.complete(match { it.model == "small" && it.reasoningEffort == "none" })
        }
        val row = rig.rows.single()
        row.served shouldBe "s2"
        row.s2!!.text shouldBe "s2"
        row.s1!!.text shouldBe "s1"
        row.s1!!.model shouldBe "small"
    }

    test("SHADOW: S1 throwing or replying empty never touches the caller's answer") {
        val rig = Rig(System1Mode.SHADOW)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        coEvery { rig.s1.complete(any()) } throws IllegalStateException("s1 down") andThen text("  ")
        runBlocking {
            rig.provider.complete(req()) shouldBe text("s2"); rig.drain()
            rig.provider.complete(req()) shouldBe text("s2"); rig.drain()
        }
        rig.rows.map { it.s1!!.error } shouldBe listOf("s1 down", "empty reply")
    }

    test("SHADOW: an S2 failure is rethrown to the caller and S1 is still logged") {
        val rig = Rig(System1Mode.SHADOW)
        coEvery { rig.s2.complete(any()) } throws IllegalStateException("s2 down")
        coEvery { rig.s1.complete(any()) } returns text("s1")
        shouldThrow<IllegalStateException> { runBlocking { rig.provider.complete(req()) } }.message shouldBe "s2 down"
        runBlocking { rig.drain() }
        val row = rig.rows.single()
        row.s2!!.error shouldBe "s2 down"
        row.s1!!.text shouldBe "s1"
    }

    // Review Focus 4
    test("SHADOW: an empty S2 reply reaches the caller unchanged, is recorded, and S1 still runs") {
        val rig = Rig(System1Mode.SHADOW)
        coEvery { rig.s2.complete(any()) } returns text("")
        coEvery { rig.s1.complete(any()) } returns text("s1")
        runBlocking { rig.provider.complete(req()) shouldBe text(""); rig.drain() }
        rig.rows.single().s2!!.error shouldBe "empty reply"
        rig.rows.single().s1!!.text shouldBe "s1"
    }

    test("SHADOW: sample rate 0 never calls S1 but still writes the row") {
        val rig = Rig(System1Mode.SHADOW, rate = 0.0)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()); rig.drain() }
        coVerify(exactly = 0) { rig.s1.complete(any()) }
        rig.rows.single().s1 shouldBe null
    }

    test("SHADOW: a second shadow while one is in flight is skipped, its row has no s1") {
        val rig = Rig(System1Mode.SHADOW)
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.s2.complete(any()) } returns text("s2")
        coEvery { rig.s1.complete(any()) } coAnswers { gate.await(); text("s1") }
        runBlocking {
            rig.provider.complete(req())   // first shadow parks on the gate
            rig.provider.complete(req())   // permit taken: skipped
            gate.complete(Unit)
            rig.drain()
        }
        coVerify(exactly = 1) { rig.s1.complete(any()) }
        rig.rows.map { it.s1 != null }.sorted() shouldBe listOf(false, true)
    }

    test("SHADOW: the background row's sink sees the caller's context elements") {
        val seen = CopyOnWriteArrayList<String?>()
        val rig = Rig(System1Mode.SHADOW, sink = { seen += currentCoroutineContext()[CoroutineName]?.name })
        coEvery { rig.s2.complete(any()) } returns text("s2")
        coEvery { rig.s1.complete(any()) } returns text("s1")
        runBlocking(CoroutineName("caller")) { rig.provider.complete(req()); rig.drain() }
        seen.toList() shouldBe listOf("caller")
    }

    // Review Focus 1
    test("SHADOW: the shadow outlives the caller's withTimeout block") {
        val rig = Rig(System1Mode.SHADOW)
        val gate = CompletableDeferred<Unit>()
        coEvery { rig.s2.complete(any()) } returns text("s2")
        coEvery { rig.s1.complete(any()) } coAnswers { gate.await(); text("s1") }
        runBlocking {
            withTimeout(5_000) { rig.provider.complete(req()) } shouldBe text("s2")
            gate.complete(Unit)
            rig.drain()
        }
        rig.rows.single().s1!!.text shouldBe "s1"
    }

    test("SYSTEM1: S1 answers and S2 is never called") {
        val rig = Rig(System1Mode.SYSTEM1)
        coEvery { rig.s1.complete(any()) } returns text("s1")
        runBlocking { rig.provider.complete(req()) } shouldBe text("s1")
        coVerify(exactly = 0) { rig.s2.complete(any()) }
        rig.rows.single().served shouldBe "s1"
        rig.rows.single().s2 shouldBe null
    }

    test("SYSTEM1: an empty S1 reply falls back to S2") {
        val rig = Rig(System1Mode.SYSTEM1)
        coEvery { rig.s1.complete(any()) } returns text("")
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()) } shouldBe text("s2")
        rig.rows.single().served shouldBe "s2"
        rig.rows.single().s1!!.error shouldBe "empty reply"
    }

    test("SYSTEM1: a thrown S1 error falls back to S2") {
        val rig = Rig(System1Mode.SYSTEM1)
        coEvery { rig.s1.complete(any()) } throws IllegalStateException("refused")
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()) } shouldBe text("s2")
        rig.rows.single().s1!!.error shouldBe "refused"
    }

    // Review Focus 2
    test("SYSTEM1: an LLMResponse.Error from S1 falls back to S2") {
        val rig = Rig(System1Mode.SYSTEM1)
        coEvery { rig.s1.complete(any()) } returns LLMResponse.Error("context overflow")
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()) } shouldBe text("s2")
        rig.rows.single().s1!!.error shouldBe "context overflow"
    }

    test("a sink that throws never changes the result, and warns once") {
        val rig = Rig(System1Mode.SYSTEM2, sink = { error("disk full") })
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking {
            rig.provider.complete(req()) shouldBe text("s2")
            rig.provider.complete(req()) shouldBe text("s2")
        }
        rig.warnings.size shouldBe 1
        rig.warnings.single() shouldContain "disk full"
    }

    test("a row serialises with v and kind present") {
        val rig = Rig(System1Mode.SYSTEM2)
        coEvery { rig.s2.complete(any()) } returns text("s2")
        runBlocking { rig.provider.complete(req()) }
        val json = rig.rows.single().toJson()
        json shouldContain "\"v\":1"
        json shouldContain "\"kind\":\"decision\""
        json shouldContain "\"judgment\":\"encoder\""
    }

    test("withSystem1 with no settings returns the same provider instance") {
        val s2 = mockk<LLMProvider>()
        withSystem1(s2, null, sink = {}, onWarning = {}) shouldBeSameInstanceAs s2
    }

    test("withSystem1 with settings wraps the provider") {
        val s2 = mockk<LLMProvider>().also { every { it.name } returns "s2" }
        withSystem1(s2, System1Settings("http://localhost:1234/v1", "small"), sink = {}, onWarning = {})
            .shouldBeInstanceOf<DualSystemProvider>()
    }
})
