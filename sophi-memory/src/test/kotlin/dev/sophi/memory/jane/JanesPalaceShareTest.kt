package dev.sophi.memory.jane

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk

class JanesPalaceShareTest : FunSpec({
    val now = 1_700_000_000_000L

    fun palace(text: String? = null, sensitivity: String = "PERSONAL", household: Boolean = false,
               home: java.nio.file.Path = tempdir().toPath()): JanesPalace {
        val llm = mockk<LLMProvider>()
        coEvery { llm.complete(any()) } returns LLMResponse.Text(
            """{"memories":[{"text":"$text","room":"KNOWLEDGE","emph":0.8,"aff":0.9,
               "sensitivity":"$sensitivity","provenance":"USER_DIRECT","household":$household}],"profile":[]}""", TokenUsage(1, 1))
        return JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llm, FakeEmbeddingProvider(), "fake")
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
})
