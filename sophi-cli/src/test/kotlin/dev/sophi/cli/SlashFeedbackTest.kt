package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class SlashFeedbackTest : FunSpec({
    test("/bad records a negative explicit record anchored to the last assistant entry") {
        val plugin = LearningPlugin(LearningConfig(home = tempdir().toPath(), scope = "/p"))
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null,
            AgentConfig(model = "m"), plugin) {}
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "q")
        session.append(EntryRole.ASSISTANT, "bad answer")

        kotlinx.coroutines.runBlocking { handler.handle("/bad wrong commit format", session) }

        val record = plugin.preferenceStore.forSession("s1").single()
        record.polarity shouldBe "negative"
        record.reason shouldBe "wrong commit format"
        record.entryIndex shouldBe 1
    }

    test("/good records a positive explicit record anchored to the last assistant entry") {
        val plugin = LearningPlugin(LearningConfig(home = tempdir().toPath(), scope = "/p"))
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null,
            AgentConfig(model = "m"), plugin) {}
        val session = AgentSession(id = "s2")
        session.append(EntryRole.USER, "q")
        session.append(EntryRole.ASSISTANT, "good answer")

        kotlinx.coroutines.runBlocking { handler.handle("/good", session) }

        val record = plugin.preferenceStore.forSession("s2").single()
        record.polarity shouldBe "positive"
        record.reason shouldBe null
        record.entryIndex shouldBe 1
    }

    test("/good with no learning plugin configured shows message without throwing") {
        val output = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null,
            AgentConfig(model = "m")) { output.add(it) }
        val session = AgentSession(id = "s3")
        session.append(EntryRole.ASSISTANT, "resp")

        kotlinx.coroutines.runBlocking { handler.handle("/good", session) }

        output shouldBe listOf("Learning is not enabled.")
    }

    test("/bad with no prior assistant entry shows nothing-to-rate message") {
        val plugin = LearningPlugin(LearningConfig(home = tempdir().toPath(), scope = "/p"))
        val output = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null,
            AgentConfig(model = "m"), plugin) { output.add(it) }
        val session = AgentSession(id = "s4")
        session.append(EntryRole.USER, "q")

        kotlinx.coroutines.runBlocking { handler.handle("/bad", session) }

        output shouldBe listOf("Nothing to rate yet.")
    }
})
