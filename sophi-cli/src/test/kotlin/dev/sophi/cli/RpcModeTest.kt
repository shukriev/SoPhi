package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import kotlinx.coroutines.flow.flowOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
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
    suspend fun rpc(stdin: String, memory: Boolean = false) = ByteArrayOutputStream().let { bytes ->
        val llm = mockk<LLMProvider>()
        every { llm.name } returns "fake"
        every { llm.stream(any()) } answers { flowOf(StreamEvent.Content("hello")) }
        val dir = tempdir().toPath()
        runRpc(
            build = { manual, onWarning ->
                val opts = optionsFor(dir).let {
                    if (memory) it.copy(memoryEnabled = true, embeddingModel = "e", embeddingBaseUrl = "http://127.0.0.1:9/v1") else it
                }
                buildCliRuntime(opts, llm, Terminal(), ScriptedInputSource(emptyList()), onWarning, manualConfirmation = manual)
            },
            switchModel = { _, _ -> null },
            stdin = ByteArrayInputStream(stdin.toByteArray()),
            stdout = PrintStream(bytes, true),
        )
        bytes.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    }

    test("stdout carries only protocol lines") {
        val events = rpc("""{"type":"prompt","text":"hi"}""" + "\n")
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

    test("--mode is not a setting") {
        CLI_SETTINGS.none { it.key == "mode" } shouldBe true
    }
})
