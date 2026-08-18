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

    test("explicit bad then good within retryWindow links the pair mechanically by record id") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p"))
        p.recordExplicitFeedback("s1", entryIndex = 2, polarity = "negative", reason = "wrong style")
        p.recordExplicitFeedback("s1", entryIndex = 5, polarity = "positive", reason = null)
        val records = p.preferenceStore.forSession("s1").associateBy { it.entryIndex }
        val negative = records.getValue(2)
        val positive = records.getValue(5)
        negative.pairedWith shouldBe positive.id
        positive.pairedWith shouldBe negative.id
    }

    test("good far outside retryWindow does not link") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", retryWindow = 4))
        p.recordExplicitFeedback("s1", 2, "negative", "r")
        p.recordExplicitFeedback("s1", 40, "positive", null)
        p.preferenceStore.forSession("s1").forEach { it.pairedWith shouldBe null }
    }

    test("good exactly at the retryWindow boundary still links") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", retryWindow = 4))
        p.recordExplicitFeedback("s1", 0, "negative", "r")
        p.recordExplicitFeedback("s1", 16, "positive", null)   // distance == retryWindow * 4, exactly
        val records = p.preferenceStore.forSession("s1").associateBy { it.entryIndex }
        records.getValue(0).pairedWith shouldBe records.getValue(16).id
    }

    test("good one past the retryWindow boundary does not link") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", retryWindow = 4))
        p.recordExplicitFeedback("s1", 0, "negative", "r")
        p.recordExplicitFeedback("s1", 17, "positive", null)   // distance == retryWindow * 4 + 1
        p.preferenceStore.forSession("s1").forEach { it.pairedWith shouldBe null }
    }

    test("with multiple unpaired negatives in range, the most recent one is linked") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p", retryWindow = 4))
        p.recordExplicitFeedback("s1", 0, "negative", "first try")
        p.recordExplicitFeedback("s1", 5, "negative", "second try")
        p.recordExplicitFeedback("s1", 10, "positive", null)
        val records = p.preferenceStore.forSession("s1").associateBy { it.entryIndex }
        records.getValue(10).pairedWith shouldBe records.getValue(5).id   // most recent, not the first
        records.getValue(0).pairedWith shouldBe null                     // left unpaired
    }

    test("recordExplicitFeedback never throws even when the store's directory can't be created") {
        val bad = tempdir().toPath().resolve("f").also { java.nio.file.Files.writeString(it, "x") }
        val p = LearningPlugin(LearningConfig(home = bad.resolve("sub"), scope = "/p"))
        p.recordExplicitFeedback("s1", 0, "positive", null)   // must not throw
    }

    test("recordPlanOutcome notes accumulate into recordSessionEnd's SessionOutcome.planningNote") {
        val home = tempdir().toPath()
        val p = plugin(home)
        p.recordPlanOutcome("s1", "goal \"ship it\" -> Met (2 steps, plan v1)")
        p.recordPlanOutcome("s1", "goal \"add tests\" -> Exhausted (1 step, plan v2)")
        runBlocking { p.recordSessionEnd("s1") }
        val lines = JsonlLog(home.resolve("session-outcomes.jsonl")).readAll()
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SessionOutcome.serializer(), lines.single())
        decoded.planningNote shouldBe
            "goal \"ship it\" -> Met (2 steps, plan v1)\ngoal \"add tests\" -> Exhausted (1 step, plan v2)"
    }

    test("no recordPlanOutcome call leaves planningNote null") {
        val home = tempdir().toPath()
        val p = plugin(home)
        runBlocking { p.recordSessionEnd("s1") }
        val lines = JsonlLog(home.resolve("session-outcomes.jsonl")).readAll()
        // LearningPlugin's own `json` instance is `Json { encodeDefaults = true }`, so a null
        // planningNote still serializes the key with an explicit null value rather than omitting
        // it — matching every other optional SessionOutcome field already written this way.
        lines.single() shouldContain "\"planningNote\":null"
    }

    test("contribute renders lesson recall via collectContext, ranked by the turn's userInput") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p"),
            model = "m1", embeddingProvider = FakeEmbeddingProvider())
        p.lessonStore.add(Lesson("les_a", 1L, "/p", "s", "always write tests first", "approach", useCount = 5))
        p.lessonStore.add(Lesson("les_b", 2L, "/p", "s", "database migrations need a rollback plan", "approach"))
        val registry = dev.sophi.extensions.PluginRegistry().register(p)
        val rendered = registry.collectContext("s1", "planning a database migration").single()
        rendered shouldContain "database migrations need a rollback plan"
    }

    test("contribute returns null gracefully when the embedding provider throws") {
        val home = tempdir().toPath()
        val throwing = object : dev.sophi.ai.api.EmbeddingProvider {
            override val dimensions = 64
            override suspend fun embed(texts: List<String>): List<FloatArray> = error("endpoint down")
        }
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p"), embeddingProvider = throwing)
        p.lessonStore.add(Lesson("les_a", 1L, "/p", "s", "a tip", "approach"))
        val registry = dev.sophi.extensions.PluginRegistry().register(p)
        registry.collectContext("s1", "anything") shouldBe emptyList()
    }

    test("promptSections now returns only reliability content, never lessons") {
        val home = tempdir().toPath()
        val p = LearningPlugin(LearningConfig(home = home, scope = "/p"))
        p.lessonStore.add(Lesson("les_a", 1L, "/p", "s", "a distinctive lesson string", "approach"))
        val sections = p.promptSections("/p")
        (sections == null || !sections.contains("a distinctive lesson string")) shouldBe true
    }
})
