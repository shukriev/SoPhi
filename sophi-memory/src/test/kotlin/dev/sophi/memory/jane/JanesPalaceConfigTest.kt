package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JanesPalaceConfigTest : FunSpec({
    test("autoPurgeEnabledFromEnv defaults to true when the variable is unset") {
        JanesPalaceConfig.autoPurgeEnabledFromEnv { null } shouldBe true
    }

    test("the literal string false, in any case, disables auto-purge") {
        JanesPalaceConfig.autoPurgeEnabledFromEnv { "false" } shouldBe false
        JanesPalaceConfig.autoPurgeEnabledFromEnv { "FALSE" } shouldBe false
        JanesPalaceConfig.autoPurgeEnabledFromEnv { "False" } shouldBe false
    }

    test("any other value -- typo, empty, or a different word -- keeps auto-purge on") {
        listOf("", "no", "0", "disabled", " false ").forEach { value ->
            JanesPalaceConfig.autoPurgeEnabledFromEnv { value } shouldBe true
        }
    }

    test("patternRepThreshold defaults to 0.5") {
        JanesPalaceConfig().patternRepThreshold shouldBe 0.5
    }

    test("commitmentExpiryMs defaults to 30 days") {
        JanesPalaceConfig().commitmentExpiryMs shouldBe 30 * 24 * 3_600_000L
    }
})
