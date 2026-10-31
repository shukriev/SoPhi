package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.BashJobs
import dev.sophi.core.tools.BashTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SlashJobsTest : FunSpec({
    test("/jobs lists and kills") {
        val jobs = BashJobs(tempdir().toPath())
        runBlocking { withContext(SessionIdContext("s")) { BashTool(tempdir().toPath(), jobs = jobs).execute("""{"command":"sleep 30","background":true}""") } }
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), jobs = jobs) { out += it }
        runBlocking {
            handler.handle("/jobs", AgentSession(id = "s"))
            out.joinToString("\n") shouldContain "j1"
            out.joinToString("\n") shouldContain "running"
            out.clear()
            handler.handle("/jobs kill j1", AgentSession(id = "s"))
        }
        out.joinToString("\n") shouldContain "killed"
    }

    test("/jobs with none running says so") {
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), jobs = BashJobs(tempdir().toPath())) { out += it }
        runBlocking { handler.handle("/jobs", AgentSession(id = "s")) }
        out.single() shouldContain "No background jobs"
    }
})
