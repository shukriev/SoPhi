package dev.sophi.learning

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.system1.Judgments
import dev.sophi.core.session.SessionIdContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class System1WiringTest : FunSpec({
    fun s2() = mockk<LLMProvider>().also {
        every { it.name } returns "s2"
        coEvery { it.complete(any()) } returns LLMResponse.Text("ok", TokenUsage(1, 1))
    }
    val tagged = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), model = "big", judgment = Judgments.ENCODER)

    test("no system1.json: the provider comes back untouched") {
        val s2 = s2()
        system1FromHome(s2, tempdir().toPath()) {} shouldBeSameInstanceAs s2
    }

    // Review Focus 5
    test("system1.json without modes: tagged calls write SYSTEM2 rows carrying the session id, S1 never contacted") {
        val home = tempdir().toPath()
        // An unreachable S1 endpoint: any S1 call would fail the reply and show up as an s1 error.
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://127.0.0.1:9/v1","model":"small"}""")
        val wrapped = system1FromHome(s2(), home) {}

        runBlocking(SessionIdContext("s-1")) { wrapped.complete(tagged) } shouldBe LLMResponse.Text("ok", TokenUsage(1, 1))

        val row = Files.readAllLines(home.resolve("system1").resolve("decisions.jsonl")).single()
        row shouldContain "\"sessionId\":\"s-1\""
        row shouldContain "\"mode\":\"SYSTEM2\""
        row shouldContain "\"s1\":null"
    }

    test("untagged calls write nothing") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://127.0.0.1:9/v1","model":"small"}""")
        runBlocking { system1FromHome(s2(), home) {}.complete(tagged.copy(judgment = null)) }
        Files.exists(home.resolve("system1").resolve("decisions.jsonl")) shouldBe false
    }
})
