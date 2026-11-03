package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class SlashSearchTest : FunSpec({
    test("/search finds past sessions") {
        val sessions = FileSessionManager(tempdir().toPath())
        sessions.save(AgentSession("past", title = "Ledger chat").apply { append(EntryRole.USER, "we chose Postgres") })
        val out = mutableListOf<String>()
        val handler = SlashHandler(sessions, null, AgentConfig(model = "m")) { out += it }
        runBlocking {
            handler.handle("/search postgres", AgentSession(id = "s"))
            out.joinToString("\n") shouldContain "Ledger chat"
            out.clear()
            handler.handle("/search", AgentSession(id = "s"))
        }
        out.single() shouldBe "Usage: /search <words>"
    }
})
