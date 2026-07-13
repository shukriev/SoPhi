package dev.sophi.cli

import com.github.ajalt.clikt.testing.test
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
import java.io.ByteArrayInputStream
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

    test("an unrecognized --min-judgment is rejected with a clear error, not a stack trace") {
        val result = ExportCommand().test("--min-judgment succes")
        result.output shouldContain "Error: --min-judgment must be success|partial"
    }

    test("a --split outside (0.0, 1.0] is rejected with a clear error") {
        val result = ExportCommand().test("--split 1.5")
        result.output shouldContain "Error: --split must be a number in (0.0, 1.0]"
    }

    test("--no-redact without --force prompts, and declining aborts without exporting") {
        val outDir = tempdir().toPath().resolve("export-out")
        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream("n\n".toByteArray()))
            ExportCommand().test(
                "--no-redact --out \"$outDir\" --learning-home \"${tempdir()}\" --sessions-dir \"${tempdir()}\"")
        } finally {
            System.setIn(originalIn)
        }
        Files.exists(outDir.resolve("manifest.json")) shouldBe false
    }

    test("--no-redact without --force proceeds once the prompt is confirmed") {
        val home = tempdir().toPath()
        val sessionsDir = tempdir().toPath()
        val outDir = tempdir().toPath().resolve("export-out")
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        outcomes.append(json.encodeToString(SessionOutcome.serializer(),
            SessionOutcome(1L, "/p", "s1", "completed", judgment = "success")))
        val s1 = AgentSession(id = "s1")
        s1.append(EntryRole.USER, "hello")
        s1.append(EntryRole.ASSISTANT, "hi there")
        FileSessionManager(sessionsDir).save(s1)

        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream("y\n".toByteArray()))
            ExportCommand().test(
                "--no-redact --out \"$outDir\" --learning-home \"$home\" --sessions-dir \"$sessionsDir\"")
        } finally {
            System.setIn(originalIn)
        }
        Files.exists(outDir.resolve("manifest.json")) shouldBe true
    }
})
