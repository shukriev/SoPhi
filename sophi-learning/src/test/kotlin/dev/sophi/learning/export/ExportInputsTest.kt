package dev.sophi.learning.export

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.learning.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ExportInputsTest : FunSpec({
    val json = Json { encodeDefaults = true }

    test("folds outcomes, filters tombstones, pairs dpo links") {
        val home = tempdir().toPath(); val sessions = tempdir().toPath()
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        outcomes.append(json.encodeToString(SessionOutcome.serializer(),
            SessionOutcome(1, "/p", "sA", "open")))
        outcomes.append(json.encodeToString(SessionOutcome.serializer(),
            SessionOutcome(2, "/p", "sA", "completed", judgment = "success")))
        val prefs = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
        prefs.add(PreferenceRecord("pref_n", 1L, "/p", "sB", 2, "negative", "explicit", reason = "r"))
        prefs.add(PreferenceRecord("pref_p", 2L, "/p", "sB", 5, "positive", "explicit"))
        prefs.link("pref_n", "pref_p")
        prefs.add(PreferenceRecord("pref_x", 3L, "/p", "sC", 1, "negative", "explicit"))
        prefs.add(PreferenceRecord("pref_d", 4L, "/p", "sD", 1, "negative", "explicit"))
        prefs.delete("pref_d")

        // A subagent session with a config snapshot, written via FileSessionManager.
        val sessionManager = FileSessionManager(sessions)
        val session = AgentSession(id = "sA", parentSessionId = "parentSession")
        session.append(EntryRole.USER, "hello")
        sessionManager.save(session)
        sessionManager.saveConfigSnapshot("sA", "gpt-x", "be nice")

        val inputs = ExportInputs(home, sessions)
        inputs.judgedOutcomes().getValue("sA").judgment shouldBe "success"
        inputs.dpoLinks().single().first.id shouldBe "pref_n"
        inputs.dpoLinks().single().second.id shouldBe "pref_p"
        inputs.negativeSessions() shouldBe setOf("sB", "sC")

        inputs.loadSession("sA")!!.entries.single().content shouldBe "hello"
        inputs.loadSession("missing").shouldBeNull()
        inputs.configSnapshot("sA") shouldBe ("gpt-x" to "be nice")
        inputs.isSubagent("sA") shouldBe true
    }

    test("dpoLinks resolves a pair by id alone, not by grouping records under one session") {
        // Regression: dpoLinks() must not assume a linked pair shares one sessionId — resolving
        // strictly by id means the code's correctness doesn't rest on that invariant holding.
        val home = tempdir().toPath()
        val prefs = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
        prefs.add(PreferenceRecord("pref_neg", 1L, "/p", "sessionX", 2, "negative", "explicit"))
        prefs.add(PreferenceRecord("pref_pos", 2L, "/p", "sessionY", 5, "positive", "explicit"))
        prefs.link("pref_neg", "pref_pos")

        val inputs = ExportInputs(home, tempdir().toPath())
        val (neg, pos) = inputs.dpoLinks().single()
        neg.id shouldBe "pref_neg"
        pos.id shouldBe "pref_pos"
    }
})
