package dev.sophi.companion.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AmbientListenerTest : FunSpec({
    test("known whisper hallucination artifacts are recognized case- and punctuation-insensitively") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.isHallucination("you") shouldBe true
        listener.isHallucination("Thank you.") shouldBe true
        listener.isHallucination("[BLANK_AUDIO]") shouldBe true
        listener.isHallucination("call the dentist") shouldBe false
    }

    test("shouldFlush is true immediately once something has been accumulated, before the first flush") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.shouldFlush(nowMs = 0) shouldBe false // nothing accumulated yet
        listener.accumulate("hello")
        listener.shouldFlush(nowMs = 0) shouldBe true
    }

    test("shouldFlush waits flushIntervalMs after the previous flush") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.accumulate("hello")
        listener.flush(nowMs = 0)
        listener.accumulate("world")
        listener.shouldFlush(nowMs = 500) shouldBe false
        listener.shouldFlush(nowMs = 1000) shouldBe true
    }

    test("flush returns null and clears nothing when the buffer is empty") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.flush(nowMs = 0) shouldBe null
    }

    test("flush returns one chunk for short accumulated text") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.accumulate("hello")
        listener.accumulate("world")
        val batch = listener.flush(nowMs = 0)!!
        batch.chunks shouldBe listOf("hello world")
    }

    test("flush splits text longer than 1800 chars into multiple chunks, each under the limit") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        val word = "a".repeat(50)
        repeat(50) { listener.accumulate(word) } // 50 * 51 chars > 1800
        val batch = listener.flush(nowMs = 0)!!
        batch.chunks.size shouldBe 2
        batch.chunks.forEach { it.length shouldBe (it.length.coerceAtMost(1800)) }
        batch.chunks.joinToString(" ") shouldBe List(50) { word }.joinToString(" ")
    }

    test("looksLikeReminder is true when accumulated text contains a reminder keyword") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.accumulate("remind me to call the dentist")
        listener.flush(nowMs = 0)!!.looksLikeReminder shouldBe true
    }

    test("looksLikeReminder is false for ordinary chatter") {
        val listener = AmbientListener(flushIntervalMs = 1000)
        listener.accumulate("the weather has been nice lately")
        listener.flush(nowMs = 0)!!.looksLikeReminder shouldBe false
    }

    test("buildAmbientReminderPrompt embeds the transcript and instructs silence when nothing matches") {
        val prompt = buildAmbientReminderPrompt("remind me to call the dentist tomorrow")
        prompt shouldBe """
            Ambient conversation (not directed at you) may contain a reminder or task
            request. If so, call manage_scheduled_task to create it. If not, do nothing
            and reply with nothing.

            Transcript: remind me to call the dentist tomorrow
        """.trimIndent()
    }
})
