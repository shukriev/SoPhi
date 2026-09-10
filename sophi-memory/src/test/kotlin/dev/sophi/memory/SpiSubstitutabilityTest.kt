package dev.sophi.memory

import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.jane.EncoderVerdict
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.memory.jane.MemoryWriter
import dev.sophi.memory.jane.PalaceStore
import dev.sophi.memory.jane.UserProfile
import dev.sophi.memory.jane.VerdictMemory
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk

/**
 * The SPI-holds test (spec §12): JanesPalace and a trivial fake both drive the full
 * observe -> recall -> forget loop through MemoryTechnique alone.
 */
class SpiSubstitutabilityTest : FunSpec({
    test("browse filters by provenance") {
        val home = tempdir().toPath()
        val embeddings = FakeEmbeddingProvider()
        val store = PalaceStore(home)
        val writer = MemoryWriter(store, UserProfile(store), embeddings, "fake", JanesPalaceConfig())
        writer.write(
            TurnObservation("s1", "u", "a", 1_000L),
            EncoderVerdict(listOf(
                VerdictMemory(text = "Alex moved to Denver", room = "ENTITIES", emph = 0.8, aff = 0.6, provenance = "THIRD_PARTY"),
                VerdictMemory(text = "I have a dentist appointment", room = "TASKS", emph = 0.8, aff = 0.6, provenance = "USER_DIRECT")
            ))
        )
        store.close() // ArcadeDB is single-open — JanesPalace below opens its own PalaceStore on the same home path

        val palace = JanesPalace(
            JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = embeddings
        )

        palace.browse(BrowseFilter(provenance = "THIRD_PARTY")).map { it.text } shouldBe listOf("Alex moved to Denver")
        palace.browse(BrowseFilter()).size shouldBe 2
    }

    test("JanesPalace end-to-end through the SPI: observe, recall, forget-all") {
        val llm = mockk<dev.sophi.ai.api.LLMProvider>()
        coEvery { llm.complete(any()) } returns LLMResponse.Text(
            """{"memories":[{"text":"User's daughter Emma starts school Monday","room":"ENTITIES",
               "emph":0.7,"aff":0.6}],"profile":[{"path":"family.daughter.name","value":"Emma"}]}""",
            TokenUsage(1, 1))
        // routeTopK = 5: with hash-fake embeddings, descriptor routing is arbitrary — search all
        // rooms so the test exercises recall, not routing luck (routing has its own test).
        val palace: MemoryTechnique = JanesPalace(
            JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "test-model", routeTopK = 5),
            llm, FakeEmbeddingProvider(), "fake")

        palace.observe(TurnObservation("s1", "Emma starts school Monday", "Noted!", 1_000L))
        palace.browse(BrowseFilter()).size shouldBe 1

        val block = palace.recall(RecallQuery("s1", "when does Emma start school", 2_000L))!!
        block.rendered shouldContain "Emma"
        palace.profileView().single().path shouldBe "family.daughter.name"

        palace.forget(ForgetRequest.All)
        palace.browse(BrowseFilter()) shouldBe emptyList()
        palace.recall(RecallQuery("s1", "when does Emma start school", 3_000L)) shouldBe null
    }
})
