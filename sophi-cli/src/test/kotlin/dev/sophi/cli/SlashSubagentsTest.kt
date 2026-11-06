package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.BackgroundSubagents
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

class SlashSubagentsTest : FunSpec({
    fun handler(bg: BackgroundSubagents?, out: MutableList<String>) =
        SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), subagents = bg) { out += it }

    test("/subagents lists them") {
        BackgroundSubagents().use { bg ->
            bg.start("s", "explore", "find the auth code", "sub-1", EmptyCoroutineContext) { awaitCancellation() }
            val out = mutableListOf<String>()
            runBlocking { handler(bg, out).handle("/subagents", AgentSession(id = "s")) }
            out shouldBe listOf("b1  running  explore  0s  find the auth code")
        }
    }

    test("/subagents cancel stops one") {
        BackgroundSubagents().use { bg ->
            bg.start("s", "explore", "look", "sub-1", EmptyCoroutineContext) { awaitCancellation() }
            val out = mutableListOf<String>()
            runBlocking {
                handler(bg, out).handle("/subagents cancel b1", AgentSession(id = "s"))
                handler(bg, out).handle("/subagents cancel b9", AgentSession(id = "s"))
                handler(bg, out).handle("/subagents cancel", AgentSession(id = "s"))
            }
            out shouldBe listOf("cancelled b1", "No such subagent b9", "Usage: /subagents cancel <id>")
            bg.list().single().status shouldBe BackgroundSubagents.Status.CANCELLED
        }
    }

    test("/subagents with none says so") {
        val out = mutableListOf<String>()
        runBlocking {
            handler(BackgroundSubagents(), out).handle("/subagents", AgentSession(id = "s"))
            handler(null, out).handle("/subagents", AgentSession(id = "s"))
        }
        out shouldBe listOf("No background subagents.", "Background subagents aren't available.")
    }
})
