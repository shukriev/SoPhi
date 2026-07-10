package dev.sophi.learning

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk

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
})
