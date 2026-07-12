package dev.sophi.cli

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.SessionOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.engine.spec.tempdir
import kotlinx.serialization.json.Json
import java.nio.file.Files

class ExportCommandTest : FunSpec({
    val json = Json { encodeDefaults = true }

    test("ExportRunner outputs counts and creates manifest.json") {
        val home = tempdir().toPath()
        val sessionsDir = tempdir().toPath()
        val outDir = tempdir().toPath().resolve("export-out")

        // Seed a minimal fixture: one success session
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val sessionManager = FileSessionManager(sessionsDir)

        fun outcome(sessionId: String, judgment: String?) =
            outcomes.append(json.encodeToString(SessionOutcome.serializer(),
                SessionOutcome(1L, "/p", sessionId, "completed", judgment = judgment)))

        val s1 = AgentSession(id = "s1")
        s1.append(EntryRole.USER, "hello")
        s1.append(EntryRole.ASSISTANT, "hi there")
        sessionManager.save(s1)
        outcome("s1", "success")

        val output = StringBuilder()
        ExportRunner(home, sessionsDir, dev.sophi.learning.export.ExportOptions(outDir = outDir)) {
            output.appendLine(it)
        }.run()

        val outStr = output.toString()
        outStr shouldContain "sft: 1 examples"
        outStr shouldContain "dpo: 0 pairs"
        outStr shouldContain "manifest:"

        // Check that manifest.json exists
        val manifestFile = outDir.resolve("manifest.json")
        Files.exists(manifestFile) shouldBe true
    }
})
