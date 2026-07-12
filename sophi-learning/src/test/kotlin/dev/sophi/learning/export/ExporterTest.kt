package dev.sophi.learning.export

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.PreferenceRecord
import dev.sophi.learning.PreferenceStore
import dev.sophi.learning.SessionOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class ExporterTest : FunSpec({
    val json = Json { encodeDefaults = true }

    fun buildFixture(home: java.nio.file.Path, sessionsDir: java.nio.file.Path) {
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val prefs = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
        val sessionManager = FileSessionManager(sessionsDir)

        fun outcome(sessionId: String, judgment: String?) =
            outcomes.append(json.encodeToString(SessionOutcome.serializer(),
                SessionOutcome(1L, "/p", sessionId, "completed", judgment = judgment)))

        // s1, s2: two success sessions with identical content (dedup candidate).
        val s1 = AgentSession(id = "s1")
        s1.append(EntryRole.USER, "hello")
        s1.append(EntryRole.ASSISTANT, "hi there")
        sessionManager.save(s1)
        outcome("s1", "success")

        val s2 = AgentSession(id = "s2")
        s2.append(EntryRole.USER, "hello")
        s2.append(EntryRole.ASSISTANT, "hi there")
        sessionManager.save(s2)
        outcome("s2", "success")

        // s3: failure session, excluded by judgment rank.
        val s3 = AgentSession(id = "s3")
        s3.append(EntryRole.USER, "help")
        s3.append(EntryRole.ASSISTANT, "no")
        sessionManager.save(s3)
        outcome("s3", "failure")

        // s4: success session but has an active negative preference (unpaired) -> excluded.
        val s4 = AgentSession(id = "s4")
        s4.append(EntryRole.USER, "x")
        s4.append(EntryRole.ASSISTANT, "y")
        sessionManager.save(s4)
        outcome("s4", "success")
        prefs.add(PreferenceRecord("pref_neg4", 1L, "/p", "s4", 1, "negative", "explicit", reason = "r"))

        // s5: success session with a clean, linked DPO pair (from PreferenceStoreTest's shape).
        val s5 = AgentSession(id = "s5")
        s5.append(EntryRole.USER, "write a commit message")
        s5.append(EntryRole.ASSISTANT, "Updated stuff.")               // entryIndex 1 = rejected
        s5.append(EntryRole.USER, "no, conventional commits please")
        s5.append(EntryRole.ASSISTANT, "feat(core): add X")            // entryIndex 3 = chosen
        sessionManager.save(s5)
        outcome("s5", "success")
        prefs.add(PreferenceRecord("pref_n5", 2L, "/p", "s5", 1, "negative", "explicit", reason = "r"))
        prefs.add(PreferenceRecord("pref_p5", 3L, "/p", "s5", 3, "positive", "explicit"))
        prefs.link("pref_n5", "pref_p5")

        // s6: linked DPO pair but with a tool round between rejected and chosen -> unpairable.
        val s6 = AgentSession(id = "s6")
        s6.append(EntryRole.USER, "write a commit message2")
        s6.append(EntryRole.ASSISTANT, "Updated stuff2.")               // entryIndex 1 = rejected
        s6.append(EntryRole.TOOL_RESULT, "x", mapOf("replay" to "false", "toolName" to "t"))
        s6.append(EntryRole.USER, "no again")
        s6.append(EntryRole.ASSISTANT, "feat(core): add Y")             // entryIndex 4 = chosen
        sessionManager.save(s6)
        outcome("s6", "success")
        prefs.add(PreferenceRecord("pref_n6", 4L, "/p", "s6", 1, "negative", "explicit", reason = "r"))
        prefs.add(PreferenceRecord("pref_p6", 5L, "/p", "s6", 4, "positive", "explicit"))
        prefs.link("pref_n6", "pref_p6")
    }

    test("orchestrates eligibility, dedup, dpo pairing, and manifest provenance") {
        val home = tempdir().toPath()
        val sessionsDir = tempdir().toPath()
        val outDir = tempdir().toPath().resolve("export-out")
        buildFixture(home, sessionsDir)

        val inputs = ExportInputs(home, sessionsDir)
        val exporter = Exporter(inputs)
        val options = ExportOptions(outDir = outDir)
        val result = exporter.export(options)

        val sftFile = outDir.resolve("sft.jsonl")
        val dpoFile = outDir.resolve("dpo.jsonl")
        val manifestFile = outDir.resolve("manifest.json")

        Files.readAllLines(sftFile).filter { it.isNotBlank() }.size shouldBe 1
        Files.readAllLines(dpoFile).filter { it.isNotBlank() }.size shouldBe 1
        result.sftExamples shouldBe 1
        result.dpoPairs shouldBe 1
        result.manifestPath shouldBe manifestFile

        val manifest = Json.parseToJsonElement(Files.readAllLines(manifestFile).joinToString("")).jsonObject
        val counts = manifest.getValue("counts").jsonObject
        counts.getValue("sftExamples").jsonPrimitive.int shouldBe 1
        counts.getValue("dpoPairs").jsonPrimitive.int shouldBe 1
        counts.getValue("sessionsScanned").jsonPrimitive.int shouldBe 6
        counts.getValue("sessionsEligible").jsonPrimitive.int shouldBe 2
        counts.getValue("duplicatesDropped").jsonPrimitive.int shouldBe 1
        counts.getValue("unpairableLinks").jsonPrimitive.int shouldBe 1
        counts.getValue("legacySessions").jsonPrimitive.int shouldBe 2
        counts.getValue("unpairedNegatives").jsonPrimitive.int shouldBe 1

        val redaction = manifest.getValue("redaction").jsonObject
        redaction.getValue("enabled").jsonPrimitive.boolean shouldBe true

        val filters = manifest.getValue("filters").jsonObject
        filters.getValue("scope").jsonPrimitive.content shouldBe ""

        // Second export without force should throw.
        shouldThrow<IllegalStateException> { exporter.export(options) }
    }

    test("session with corrupt toolCalls metadata is skipped without aborting the run") {
        val home = tempdir().toPath()
        val sessionsDir = tempdir().toPath()
        val outDir = tempdir().toPath().resolve("export-out")

        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val sessionManager = FileSessionManager(sessionsDir)
        fun outcome(sessionId: String) =
            outcomes.append(json.encodeToString(SessionOutcome.serializer(),
                SessionOutcome(1L, "/p", sessionId, "completed", judgment = "success")))

        val healthy = AgentSession(id = "sHealthy")
        healthy.append(EntryRole.USER, "hi")
        healthy.append(EntryRole.ASSISTANT, "yo")
        sessionManager.save(healthy)
        outcome("sHealthy")

        val corrupt = AgentSession(id = "sCorrupt")
        corrupt.append(EntryRole.USER, "call a tool")
        corrupt.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to "NOT-VALID-JSON{{"))
        sessionManager.save(corrupt)
        outcome("sCorrupt")

        val inputs = ExportInputs(home, sessionsDir)
        val exporter = Exporter(inputs)
        val result = exporter.export(ExportOptions(outDir = outDir))

        result.sftExamples shouldBe 1
        val manifest = Json.parseToJsonElement(
            Files.readAllLines(outDir.resolve("manifest.json")).joinToString("")
        ).jsonObject
        manifest.getValue("counts").jsonObject.getValue("sessionsEligible").jsonPrimitive.int shouldBe 2
    }

    test("legacy sessions without config snapshot are counted, non-legacy sessions with config snapshot are not") {
        val home = tempdir().toPath()
        val sessionsDir = tempdir().toPath()
        val outDir = tempdir().toPath().resolve("export-out")

        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val sessionManager = FileSessionManager(sessionsDir)
        fun outcome(sessionId: String) =
            outcomes.append(json.encodeToString(SessionOutcome.serializer(),
                SessionOutcome(1L, "/p", sessionId, "completed", judgment = "success")))

        // Legacy session: no tool rounds, no config snapshot.
        val legacy = AgentSession(id = "sLegacy")
        legacy.append(EntryRole.USER, "hello")
        legacy.append(EntryRole.ASSISTANT, "hi there")
        sessionManager.save(legacy)
        outcome("sLegacy")

        // Non-legacy session: no tool rounds but has config snapshot.
        val nonLegacy = AgentSession(id = "sNonLegacy")
        nonLegacy.append(EntryRole.USER, "hello")
        nonLegacy.append(EntryRole.ASSISTANT, "hi there")
        sessionManager.save(nonLegacy)
        sessionManager.saveConfigSnapshot("sNonLegacy", "claude-3-sonnet", "You are a helpful assistant.")
        outcome("sNonLegacy")

        val inputs = ExportInputs(home, sessionsDir)
        val exporter = Exporter(inputs)
        val result = exporter.export(ExportOptions(outDir = outDir))

        val manifest = Json.parseToJsonElement(
            Files.readAllLines(outDir.resolve("manifest.json")).joinToString("")
        ).jsonObject
        val counts = manifest.getValue("counts").jsonObject

        // Legacy session (sLegacy) should increment legacySessions count.
        counts.getValue("legacySessions").jsonPrimitive.int shouldBe 1
        // Both sessions should be eligible and contribute to SFT examples (before dedup).
        counts.getValue("sessionsEligible").jsonPrimitive.int shouldBe 2
        // After dedup, both should remain (different UUIDs in content).
        result.sftExamples shouldBe 2
    }
})
