package dev.sophi.memory

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files

private const val DAY = 24 * 3_600_000L

/**
 * The mini longitudinal benchmark (spec §11.3): a synthetic user across simulated weeks.
 * The encoder LLM is scripted per turn; embeddings are the deterministic fake; time jumps.
 */
class ScenarioBenchmarkTest : FunSpec({

    fun verdict(json: String): LLMResponse = LLMResponse.Text(json, TokenUsage(1, 1))

    test("90-day life: recall, decay, correction, verification, forget — in one story") {
        val home = Files.createTempDirectory("palace-bench")
        val llm = mockk<LLMProvider>()
        // routeTopK = 5 for the same reason as SpiSubstitutabilityTest: descriptor routing is
        // meaningless under hash-fake embeddings; the benchmark tests memory behavior, not routing.
        val palace = JanesPalace(
            JanesPalaceConfig(home = home, sessionModel = "m", routeTopK = 5), llm, FakeEmbeddingProvider(), "fake")

        // Day 0, session 1: an entity, a task, and a sensitive disclosure.
        coEvery { llm.complete(any()) } returns verdict("""{"memories":[
            {"text":"User's daughter Emma starts school on Monday","room":"ENTITIES","emph":0.7,"aff":0.5}],
            "profile":[{"path":"family.daughter.name","value":"Emma"}]}""")
        palace.observe(TurnObservation("s1", "my daughter Emma starts school Monday", "Noted", 0L))
        coEvery { llm.complete(any()) } returns verdict("""{"memories":[
            {"text":"Dentist appointment Thursday 14:00","room":"TASKS","emph":0.8,"aff":0.1}],"profile":[]}""")
        palace.observe(TurnObservation("s1", "dentist thursday at 2pm", "Noted", 1L))
        coEvery { llm.complete(any()) } returns verdict("""{"memories":[
            {"text":"User was diagnosed with hypertension","room":"KNOWLEDGE","emph":0.3,"aff":0.9,
             "sensitivity":"SENSITIVE"}],"profile":[]}""")
        palace.observe(TurnObservation("s1", "doctor says I have hypertension", "I'm sorry", 2L))

        // Day 2: the entity fact is recalled; the sensitive fact does not tag along.
        val d2 = palace.recall(RecallQuery("s2", "when does Emma start school", 2 * DAY))!!
        d2.rendered shouldContain "Emma"
        d2.rendered shouldNotContain "hypertension"

        // Day 2: sensitive fact IS available when the user raises the topic. (Query is the bare
        // topic token: fake-embedding cosine for "hypertension" vs the 5-token memory ≈ 0.45,
        // clearing the 0.35 sensitive floor; longer paraphrases dilute below it.)
        palace.recall(RecallQuery("s2", "hypertension", 2 * DAY))!!
            .rendered shouldContain "hypertension"

        // Day 5: the task (72h..7d half-lives) now needs verification language; entity does not.
        val d5 = palace.recall(RecallQuery("s3", "dentist appointment thursday", 5 * DAY))!!
        d5.rendered.lines().single { it.contains("Dentist") } shouldContain "VERIFY"

        // Day 5: correction supersedes.
        coEvery { llm.complete(any()) } answers {
            val prompt = (firstArg<dev.sophi.ai.api.CompletionRequest>()).messages.single().content
            val oldId = Regex("\\[(mem_[0-9a-f-]+)\\] \\(TASKS\\)").find(prompt)!!.groupValues[1]
            verdict("""{"memories":[{"text":"Dentist appointment moved to Friday 10:00","room":"TASKS",
                "emph":0.8,"aff":0.1,"supersedes":"$oldId"}],"profile":[]}""")
        }
        palace.observe(TurnObservation("s3", "dentist moved to friday 10am", "Updated", 5 * DAY))
        val afterFix = palace.recall(RecallQuery("s3", "dentist appointment when", 5 * DAY + 1))!!
        afterFix.rendered shouldContain "Friday"
        afterFix.rendered shouldNotContain "Thursday"

        // Day 40: untouched TASKS memory has decayed out of recall; ENTITIES persists.
        palace.recall(RecallQuery("s4", "does Emma go to school", 40 * DAY))!!
            .rendered shouldContain "Emma"

        // Day 40: forget the diagnosis — provably unretrievable everywhere (spec §12).
        // Refresh last-recall.txt with the sensitive memory's text so the completeness
        // audit below genuinely guards the forget-time last-recall scrub (Task 9 fix).
        palace.recall(RecallQuery("s4", "hypertension", 40 * DAY))!!
            .rendered shouldContain "hypertension"
        val victimId = palace.browse(BrowseFilter()).single { it.text.contains("hypertension") }.id
        palace.forget(ForgetRequest.ById(victimId))
        palace.recall(RecallQuery("s4", "hypertension", 40 * DAY)) shouldBe null
        Files.list(home).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { p ->
                Files.readString(p) shouldNotContain "hypertension"
            }
        }

        // Day 40: profile survived the whole story.
        palace.profileView().single { it.path == "family.daughter.name" }.value shouldBe "Emma"
    }
})
