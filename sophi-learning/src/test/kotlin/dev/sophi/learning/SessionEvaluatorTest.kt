package dev.sophi.learning

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking

private data class EvalFixture(
    val eval: SessionEvaluator, val lessons: LessonStore,
    val outcomes: JsonlLog, val prefs: PreferenceStore
)

class SessionEvaluatorTest : FunSpec({
    val verdict = """{"judgment":"failure","reason":"tests never ran",
        "lessons":[{"text":"Use -pl targeting","kind":"environment","global":false,"supersedes":null}]}"""

    fun fixture(vararg responses: LLMResponse): Triple<SessionEvaluator, LessonStore, JsonlLog> {
        val home = tempdir().toPath()
        val lessons = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany responses.toList()
        val config = LearningConfig(home = home, scope = "/p", sessionModel = "test-model")
        return Triple(SessionEvaluator(provider, lessons, outcomes, config), lessons, outcomes)
    }
    fun fixtureWithPrefs(vararg responses: LLMResponse): EvalFixture {
        val home = tempdir().toPath()
        val lessons = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val prefs = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany responses.toList()
        val config = LearningConfig(home = home, scope = "/p", sessionModel = "test-model")
        return EvalFixture(SessionEvaluator(provider, lessons, outcomes, config, prefs), lessons, outcomes, prefs)
    }
    val mechanical = SessionOutcome(1L, "/p", "s1", "completed", turns = 2)

    test("valid verdict stores lesson and appends judged outcome") {
        val (eval, lessons, outcomes) = fixture(LLMResponse.Text(verdict, TokenUsage(1, 1)))
        kotlinx.coroutines.runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        lessons.active("/p").single().text shouldBe "Use -pl targeting"
        outcomes.readAll().last() shouldContain "\"judgment\":\"failure\""
    }

    test("malformed then repaired: one retry succeeds") {
        val (eval, lessons, _) = fixture(
            LLMResponse.Text("sure! here you go:", TokenUsage(1, 1)),
            LLMResponse.Text(verdict, TokenUsage(1, 1)))
        kotlinx.coroutines.runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        lessons.active("/p").size shouldBe 1
    }

    test("malformed twice: silent no-op") {
        val (eval, lessons, outcomes) = fixture(
            LLMResponse.Text("nope", TokenUsage(1, 1)), LLMResponse.Text("still nope", TokenUsage(1, 1)))
        kotlinx.coroutines.runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        lessons.active("/p") shouldBe emptyList()
        outcomes.readAll() shouldBe emptyList()
    }

    test("supersedes archives the referenced lesson") {
        val (eval, lessons, _) = fixture(LLMResponse.Text(
            """{"judgment":"success","reason":"ok","lessons":[
               {"text":"corrected","kind":"environment","global":false,"supersedes":"les_old"}]}""",
            TokenUsage(1, 1)))
        lessons.add(Lesson("les_old", 1L, "/p", "s0", "wrong", "environment"))
        kotlinx.coroutines.runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        lessons.active("/p").single().text shouldBe "corrected"
    }

    test("implicit feedback with evidence becomes a weighted record; without evidence is dropped") {
        val (eval, _, _, prefs) = fixtureWithPrefs(LLMResponse.Text(
            """{"judgment":"success","reason":"ok","lessons":[],
                "feedback":[
                  {"entryIndex":3,"polarity":"negative","signal":"user_corrected","evidence":"no, the OTHER file","retryOf":null},
                  {"entryIndex":5,"polarity":"negative","signal":"user_frustrated","evidence":"","retryOf":null}
                ]}""", TokenUsage(1, 1)))
        runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        val record = prefs.forSession("s1").single()
        record.source shouldBe "implicit"
        record.weight shouldBe 0.5
        record.evidence shouldBe "no, the OTHER file"
    }

    test("retryOf on an implicit item links the pair by record id") {
        val (eval, _, _, prefs) = fixtureWithPrefs(LLMResponse.Text(
            """{"judgment":"success","reason":"ok","lessons":[],
                "feedback":[
                  {"entryIndex":2,"polarity":"negative","signal":"user_corrected","evidence":"wrong","retryOf":null},
                  {"entryIndex":6,"polarity":"positive","signal":"user_satisfied","evidence":"perfect, thanks","retryOf":2}
                ]}""", TokenUsage(1, 1)))
        runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        val byEntry = prefs.forSession("s1").associateBy { it.entryIndex }
        val negative = byEntry.getValue(2)
        val positive = byEntry.getValue(6)
        negative.pairedWith shouldBe positive.id
        positive.pairedWith shouldBe negative.id
    }

    test("evaluator max tokens comes from LearningConfig, not a hardcoded value") {
        val home = tempdir().toPath()
        val lessons = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        val outcomes = JsonlLog(home.resolve("session-outcomes.jsonl"))
        val provider = mockk<LLMProvider>()
        val requestSlot = slot<CompletionRequest>()
        coEvery { provider.complete(capture(requestSlot)) } returns
            LLMResponse.Text(verdict, TokenUsage(1, 1))
        val config = LearningConfig(
            home = home, scope = "/p", sessionModel = "test-model", evaluatorMaxTokens = 4096)
        val eval = SessionEvaluator(provider, lessons, outcomes, config)

        runBlocking { eval.evaluate("s1", emptyList(), mechanical) }

        requestSlot.captured.maxTokens shouldBe 4096
    }

    test("retryOf links correctly even when the positive item appears before its negative in the array") {
        val (eval, _, _, prefs) = fixtureWithPrefs(LLMResponse.Text(
            """{"judgment":"success","reason":"ok","lessons":[],
                "feedback":[
                  {"entryIndex":6,"polarity":"positive","signal":"user_satisfied","evidence":"perfect, thanks","retryOf":2},
                  {"entryIndex":2,"polarity":"negative","signal":"user_corrected","evidence":"wrong","retryOf":null}
                ]}""", TokenUsage(1, 1)))
        runBlocking { eval.evaluate("s1", emptyList(), mechanical) }
        val byEntry = prefs.forSession("s1").associateBy { it.entryIndex }
        val negative = byEntry.getValue(2)
        val positive = byEntry.getValue(6)
        negative.pairedWith shouldBe positive.id
        positive.pairedWith shouldBe negative.id
    }
})
