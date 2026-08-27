package dev.sophi.versioning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ScorecardStoreTest : FunSpec({
    test("record() stores a scorecard linked to a configVersionId") {
        val store = ScorecardStore(createTempDirectory("scorecard-test"))

        val recorded = store.record(
            configVersionId = "cfg-1", headlineScore = 0.8,
            perCategory = mapOf("coding" to 0.9, "docs" to 0.7),
            quarantinedCaseIds = emptyList(), totalCases = 10
        )

        recorded.configVersionId shouldBe "cfg-1"
        recorded.headlineScore shouldBe 0.8
        recorded.perCategory shouldBe mapOf("coding" to 0.9, "docs" to 0.7)
    }

    test("forConfigVersion() returns every scorecard recorded for that config version") {
        val store = ScorecardStore(createTempDirectory("scorecard-test"))
        store.record("cfg-1", 0.5, emptyMap(), emptyList(), 5)
        store.record("cfg-1", 0.6, emptyMap(), emptyList(), 5)
        store.record("cfg-2", 0.9, emptyMap(), emptyList(), 5)

        store.forConfigVersion("cfg-1") shouldHaveSize 2
        store.forConfigVersion("cfg-2") shouldHaveSize 1
    }

    test("forConfigVersion() is empty for a configVersionId with no recorded scorecards") {
        val store = ScorecardStore(createTempDirectory("scorecard-test"))

        store.forConfigVersion("does-not-exist") shouldBe emptyList()
    }
})
