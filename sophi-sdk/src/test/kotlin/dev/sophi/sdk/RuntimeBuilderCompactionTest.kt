package dev.sophi.sdk

import dev.sophi.core.context.CompactionMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking

class RuntimeBuilderCompactionTest : FunSpec({
    test("SOPHI_COMPACTION selects the mode; unknown values fall back to structured") {
        fun mode(v: String?): CompactionMode {
            val runtime = RuntimeBuilder().apply {
                provider = RecordingStreamProvider(); contextWindowTokens(32_000); sessionsDir = tempdir().toPath()
                contextKnobs = listOfNotNull(v?.let { "SOPHI_COMPACTION" to it }).toMap()
            }.build()
            return runtime.compactionMode.also { runtime.close() }
        }
        mode(null) shouldBe CompactionMode.STRUCTURED
        mode(" Elide ") shouldBe CompactionMode.ELIDE
        mode("GENERIC") shouldBe CompactionMode.GENERIC
        mode("bogus") shouldBe CompactionMode.STRUCTURED
    }
    test("a long conversation is compacted across turns in an SDK runtime") {
        val provider = RecordingStreamProvider()
        val usageLog = tempdir().toPath().resolve("usage.jsonl")
        val runtime = RuntimeBuilder().apply {
            this.provider = provider; contextWindowTokens(4_000); sessionsDir = tempdir().toPath(); contextKnobs = emptyMap()
            usageLogPath = usageLog
        }.build()
        val s = runBlocking { runtime.newSession() }
        repeat(3) { i -> runBlocking { runtime.streamTurn(s, "turn $i " + "x".repeat(3_000)) {} } }
        val branch = runtime.sessionManager.load(s).branch()
        branch.first().content shouldStartWith "Summary of the conversation so far:"
        java.nio.file.Files.readString(usageLog) shouldContain "\"kind\":\"summarise\""
        runtime.close()
    }
})
