package dev.sophi.ai.system1

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class System1SettingsTest : FunSpec({
    fun file(json: String): Path = tempdir().toPath().resolve("system1.json").also { Files.writeString(it, json) }
    val base = """"baseUrl":"http://localhost:1234/v1","model":"small""""

    test("a missing file means System 1 is off") {
        System1Settings.load(tempdir().toPath().resolve("system1.json")) {}.shouldBeNull()
    }

    test("an unreadable file means System 1 is off, with a warning") {
        val warnings = mutableListOf<String>()
        System1Settings.load(file("{not json")) { warnings += it }.shouldBeNull()
        warnings.single() shouldContain "unreadable"
    }

    test("defaults: unlisted judgments are SYSTEM2 at sample rate 1.0, S1 client 20s and no retries") {
        val s = System1Settings.load(file("{$base}")) {}!!
        s.modeOf(Judgments.ENCODER) shouldBe System1Mode.SYSTEM2
        s.sampleRateOf(Judgments.ENCODER) shouldBe 1.0
        s.timeoutSeconds shouldBe 20
        s.maxRetries shouldBe 0
    }

    test("modes parse case-insensitively") {
        val s = System1Settings.load(file("""{$base,"modes":{"encoder":"shadow","compactor":"SYSTEM1"}}""")) {}!!
        s.modeOf(Judgments.ENCODER) shouldBe System1Mode.SHADOW
        s.modeOf(Judgments.COMPACTOR) shouldBe System1Mode.SYSTEM1
    }

    test("a safety judgment set to SYSTEM1 is clamped to SHADOW, with a warning") {
        val warnings = mutableListOf<String>()
        val s = System1Settings.load(file("""{$base,"modes":{"risk":"SYSTEM1","skill-security":"SYSTEM1"}}""")) { warnings += it }!!
        s.modeOf(Judgments.RISK) shouldBe System1Mode.SHADOW
        s.modeOf(Judgments.SKILL_SECURITY) shouldBe System1Mode.SHADOW
        warnings shouldHaveSize 2
        warnings[0] shouldContain "safety"
    }

    test("an unknown judgment name warns (typo guard)") {
        val warnings = mutableListOf<String>()
        System1Settings.load(file("""{$base,"modes":{"encodr":"SHADOW"}}""")) { warnings += it }
        warnings.single() shouldContain "encodr"
    }

    test("an unknown mode warns and leaves the judgment on SYSTEM2") {
        val warnings = mutableListOf<String>()
        val s = System1Settings.load(file("""{$base,"modes":{"encoder":"FAST"}}""")) { warnings += it }!!
        s.modeOf(Judgments.ENCODER) shouldBe System1Mode.SYSTEM2
        warnings.single() shouldContain "FAST"
    }

    // Review Focus 3
    test("an out-of-range shadowSampleRate is clamped to [0, 1]") {
        val s = System1Settings.load(file("""{$base,"shadowSampleRate":{"encoder":5,"compactor":-1}}""")) {}!!
        s.sampleRateOf(Judgments.ENCODER) shouldBe 1.0
        s.sampleRateOf(Judgments.COMPACTOR) shouldBe 0.0
    }

    test("a clean file produces no warnings") {
        val warnings = mutableListOf<String>()
        System1Settings.load(file("""{$base,"modes":{"encoder":"SHADOW"}}""")) { warnings += it }
        warnings.shouldBeEmpty()
    }
})
