package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.tools.Tool
import kotlinx.coroutines.flow.flowOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import com.github.ajalt.clikt.core.ProgramResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RpcModeTest : FunSpec({
    /** Runs rpc mode on [stdin] against a fake model; returns the parsed stdout lines. */
    suspend fun rpc(
        stdin: String, memory: Boolean = false, lateLine: String? = null,
        reply: () -> kotlinx.coroutines.flow.Flow<StreamEvent> = { flowOf(StreamEvent.Content("hello")) },
        tools: List<Tool> = emptyList(),
    ) = ByteArrayOutputStream().let { bytes ->
        val llm = mockk<LLMProvider>()
        every { llm.name } returns "fake"
        every { llm.stream(any()) } answers { reply() }
        val dir = tempdir().toPath()
        // The real System.in, as in production: a read from it anywhere but the rpc reader would eat a command.
        val realIn = System.`in`
        val pipe = java.io.PipedOutputStream()
        System.setIn(java.io.PipedInputStream(pipe))
        pipe.write(stdin.toByteArray())
        // Sent once the turn is well into its tool rounds, when a stdin read by anything else would be waiting for it.
        val late = kotlin.concurrent.thread { Thread.sleep(1500); lateLine?.let { pipe.write(it.toByteArray()) }; pipe.close() }
        try { runRpc(
            build = { manual, onWarning, input ->
                val opts = optionsFor(dir).let {
                    if (memory) it.copy(memoryEnabled = true, embeddingModel = "e", embeddingBaseUrl = "http://127.0.0.1:9/v1") else it
                }
                buildCliRuntime(opts, llm, Terminal(), input, onWarning, manualConfirmation = manual)
                    .also { cli -> tools.forEach(cli.registry::register) }
            },
            switchModel = { _, _ -> null },
            stdin = System.`in`,
            stdout = PrintStream(bytes, true),
        ) } finally { System.setIn(realIn); late.join() }
        bytes.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    }

    test("stdout carries only protocol lines") {
        val real = System.out
        val stray = ByteArrayOutputStream()
        System.setOut(PrintStream(stray, true))
        val events = try { rpc("""{"type":"prompt","text":"hi"}""" + "\n") } finally { System.setOut(real) }
        stray.toString() shouldBe "" // a println to the real stdout would corrupt the protocol
        events.map { it["type"]!!.jsonPrimitive.content }.let {
            it.first() shouldBe "ready"
            it.last() shouldBe "exited"
        }
    }

    test("ready reports memory off with a reason") {
        val memory = rpc("", memory = true).first()["memory"]!!.jsonObject
        memory["enabled"]!!.jsonPrimitive.boolean shouldBe false
        memory["reason"]!!.jsonPrimitive.contentOrNull!! shouldStartWith "memory:"
    }

    test("a tripped loop guard stops the turn without reading a client command from stdin") {
        val broken = object : Tool {
            override val name = "broken"
            override val description = "always fails"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String = throw RuntimeException("nope")
        }
        val events = rpc(
            """{"type":"prompt","text":"go"}""" + "\n",
            lateLine = """{"type":"prompt","text":"again"}""" + "\n",
            reply = { flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "broken", "{}")))) },
            tools = listOf(broken),
        )
        // The second prompt arrives after the first turn was stopped; it must run, not be eaten by the guard's yes/no.
        events.filter { it["type"]!!.jsonPrimitive.content == "turn_ended" }
            .map { it["stopReason"]!!.jsonPrimitive.content } shouldBe listOf("LoopGuard", "LoopGuard")
    }

    test("a runtime that cannot start writes one error line and exits non-zero") {
        val bytes = ByteArrayOutputStream()
        val result = shouldThrow<ProgramResult> {
            runRpc(
                build = { _, _, _ -> throw IllegalStateException("no model server") },
                switchModel = { _, _ -> null },
                stdin = ByteArrayInputStream(ByteArray(0)),
                stdout = PrintStream(bytes, true),
            )
        }
        result.statusCode shouldNotBe 0
        val line = bytes.toString().lines().filter { it.isNotBlank() }.single()
        Json.parseToJsonElement(line).jsonObject["type"]!!.jsonPrimitive.content shouldBe "error"
        line shouldContain "no model server"
    }

    test("--mode is not a setting") {
        CLI_SETTINGS.none { it.key == "mode" } shouldBe true
    }
})
