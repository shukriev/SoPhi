package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.RecallQuery
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class JanesPalaceShareTest : FunSpec({
    val now = 1_700_000_000_000L

    fun palace(text: String? = null, sensitivity: String = "PERSONAL", household: Boolean = false,
               home: java.nio.file.Path = tempdir().toPath(), provenance: String = "USER_DIRECT",
               keepRecallTrail: Boolean = true, embeddings: EmbeddingProvider = FakeEmbeddingProvider()): JanesPalace {
        val llm = mockk<LLMProvider>()
        coEvery { llm.complete(any()) } returns LLMResponse.Text(
            """{"memories":[{"text":"$text","room":"KNOWLEDGE","emph":0.8,"aff":0.9,"dur":0.9,
               "sensitivity":"$sensitivity","provenance":"$provenance","household":$household}],"profile":[]}""", TokenUsage(1, 1))
        val config = JanesPalaceConfig(home = home, sessionModel = "test-model", keepRecallTrail = keepRecallTrail)
        return JanesPalace(config, llm, embeddings, "fake")
    }
    suspend fun JanesPalace.stored(): String =
        observeStored(TurnObservation("s1", "u", "a", now)).single().id

    test("share copies a fact into the household") {
        val personal = palace("The plumber comes Thursday")
        val household = palace()
        val id = personal.stored()
        val before = personal.browse(BrowseFilter()).single()

        personal.share(id, household, "Alex", now)!!.text shouldBe "The plumber comes Thursday"

        val shared = household.browse(BrowseFilter()).single()
        shared.text shouldBe "The plumber comes Thursday"
        shared.metadata["sharedBy"] shouldBe "Alex"
        shared.metadata["provenance"] shouldBe "USER_DIRECT"
        personal.browse(BrowseFilter()).single() shouldBe before
        personal.close(); household.close()
    }

    test("a household candidate and its sharers survive a reopen") {
        val personalHome = tempdir().toPath(); val householdHome = tempdir().toPath()
        val personal = palace("The plumber comes Thursday", household = true, home = personalHome)
        val household = palace(home = householdHome)
        personal.share(personal.stored(), household, "Alex", now)
        personal.close(); household.close()
        palace(home = personalHome).let { again ->
            again.browse(BrowseFilter()).single().metadata["household"] shouldBe "candidate"
            again.close()
        }
        palace(home = householdHome).let { again ->
            again.browse(BrowseFilter()).single().metadata["sharedBy"] shouldBe "Alex"
            again.close()
        }
    }

    test("same fact from two people is one entry") {
        val a = palace("The plumber comes Thursday")
        val b = palace("The plumber comes Thursday")
        val household = palace()
        a.share(a.stored(), household, "Alex", now)
        b.share(b.stored(), household, "Sam", now + 1)
        b.share(b.browse(BrowseFilter()).single().id, household, "Sam", now + 2)   // again: Sam isn't added twice

        val shared = household.browse(BrowseFilter()).single()
        shared.metadata["sharedBy"] shouldBe "Alex, Sam"
        a.close(); b.close(); household.close()
    }

    test("sensitive memories can't be shared") {
        val personal = palace("The plumber comes Thursday", sensitivity = "SENSITIVE")
        val household = palace()
        personal.share(personal.stored(), household, "Alex", now).shouldBeNull()
        personal.share("mem_unknown", household, "Alex", now).shouldBeNull()
        household.browse(BrowseFilter()) shouldBe emptyList()
        personal.close(); household.close()
    }

    test("only the user's own words can be shared") {
        val household = palace()
        val heard = palace("The plumber comes Thursday", provenance = "THIRD_PARTY")
        heard.share(heard.stored(), household, "Alex", now).shouldBeNull()
        val ambient = palace("The plumber comes Thursday")
        val id = ambient.observeStored(TurnObservation("ambient", "u", "a", now, ambient = true)).single().id
        ambient.share(id, household, "Alex", now).shouldBeNull()
        household.browse(BrowseFilter()) shouldBe emptyList()
        heard.close(); ambient.close(); household.close()
    }

    test("a palace without a recall trail recalls but keeps no trail") {
        val home = tempdir().toPath()
        val household = palace("The plumber comes Thursday", home = home, keepRecallTrail = false)
        household.stored()
        household.recall(RecallQuery("s2", "plumber comes Thursday?", now)).shouldNotBeNull()
        household.explainLastRecall().shouldBeNull()
        household.close()
        java.nio.file.Files.exists(home.resolve("last-recall.txt")) shouldBe false
        PalaceStore(home).let { it.recallsSince(0) shouldBe emptyList(); it.close() }
    }

    test("two people sharing the same fact at once make one entry") {
        // Both shares embed before either looks for a match, so an unlocked lookup+write races.
        val arrived = AtomicInteger(); val both = CompletableDeferred<Unit>()
        val fake = FakeEmbeddingProvider()
        val gated = object : EmbeddingProvider by fake {
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                if (arrived.incrementAndGet() == 2) both.complete(Unit)
                withTimeoutOrNull(2_000) { both.await() }
                return fake.embed(texts)
            }
        }
        val a = palace("The plumber comes Thursday")
        val b = palace("The plumber comes Thursday")
        val household = palace(embeddings = gated)
        val ida = a.stored(); val idb = b.stored()
        coroutineScope {
            listOf(async(Dispatchers.Default) { a.share(ida, household, "Alex", now) },
                async(Dispatchers.Default) { b.share(idb, household, "Sam", now) }).awaitAll()
        }
        household.browse(BrowseFilter()).single().metadata["sharedBy"]!!.split(", ").toSet() shouldBe setOf("Alex", "Sam")
        a.close(); b.close(); household.close()
    }
})
