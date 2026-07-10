package dev.sophi.learning

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class LearningPluginTest : FunSpec({
    fun plugin(home: java.nio.file.Path) =
        LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "m1")
    fun hook(p: LearningPlugin, point: HookPoint) = p.hooks().single { it.point == point }

    test("AFTER_TOOL hook appends a ToolEvent line") {
        val home = tempdir().toPath()
        val p = plugin(home)
        runBlocking {
            hook(p, HookPoint.AFTER_TOOL).invoke(HookContext(
                "s1", toolName = "grep", toolResult = "Error: boom",
                success = false, durationMillis = 5))
        }
        val line = JsonlLog(home.resolve("tool-events.jsonl")).readAll().single()
        line shouldContain "\"tool\":\"grep\""
        line shouldContain "\"success\":false"
    }

    test("AFTER_TURN upserts an open outcome; recordSessionEnd finalizes it") {
        val home = tempdir().toPath()
        val p = plugin(home)
        runBlocking {
            hook(p, HookPoint.AFTER_TURN).invoke(HookContext("s1"))
            hook(p, HookPoint.AFTER_TURN).invoke(HookContext("s1"))
            p.recordSessionEnd("s1")
        }
        val lines = JsonlLog(home.resolve("session-outcomes.jsonl")).readAll()
        lines.size shouldBe 3
        lines.last() shouldContain "\"outcome\":\"completed\""
        lines.last() shouldContain "\"turns\":2"
    }

    test("store failures never propagate") {
        // home pointing at a *file* makes createDirectories fail
        val bad = tempdir().toPath().resolve("f").also { java.nio.file.Files.writeString(it, "x") }
        val p = LearningPlugin(LearningConfig(home = bad.resolve("sub"), scope = "/p"))
        runBlocking {
            hook(p, HookPoint.AFTER_TOOL).invoke(HookContext("s1", toolName = "t", success = true))
        } // must not throw
    }

    test("recordSessionEnd triggers evaluator which stores a lesson") {
        val home = tempdir().toPath()
        val provider = mockk<dev.sophi.ai.api.LLMProvider>()
        coEvery { provider.complete(any()) } returns dev.sophi.ai.api.LLMResponse.Text(
            """{"judgment":"success","reason":"ok","lessons":[
               {"text":"lesson!","kind":"approach","global":false,"supersedes":null}]}""",
            dev.sophi.ai.api.TokenUsage(1, 1))
        val sm = dev.sophi.core.session.FileSessionManager(tempdir().toPath())
        val session = sm.create().also { sm.save(it) }
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", sessionModel = "m"),
            model = "m", provider = provider, sessionManager = sm)
        runBlocking { p.recordSessionEnd(session.id) }
        p.lessonStore.active("/p").single().text shouldBe "lesson!"
    }

    test("explicit bad then good within retryWindow links the pair mechanically") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p"))
        p.recordExplicitFeedback("s1", entryIndex = 2, polarity = "negative", reason = "wrong style")
        p.recordExplicitFeedback("s1", entryIndex = 5, polarity = "positive", reason = null)
        val records = p.preferenceStore.forSession("s1").associateBy { it.entryIndex }
        records.getValue(2).pairedWith shouldBe 5
        records.getValue(5).pairedWith shouldBe 2
    }

    test("good far outside retryWindow does not link") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", retryWindow = 4))
        p.recordExplicitFeedback("s1", 2, "negative", "r")
        p.recordExplicitFeedback("s1", 40, "positive", null)
        p.preferenceStore.forSession("s1").forEach { it.pairedWith shouldBe null }
    }
})
