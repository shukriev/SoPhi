package dev.sophi.web.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AgentConfigurationTest : FunSpec({
    test("buildProviderFromProperties builds a claude provider when type=claude") {
        val props = ProviderProperties(type = "claude", model = "claude-opus-4-8", apiKey = "sk-ant-test")
        buildProviderFromProperties(props).name shouldBe "claude"
    }

    test("buildProviderFromProperties builds an openai-compat provider when type=openai-compat") {
        val props = ProviderProperties(
            type = "openai-compat",
            model = "qwen2.5:7b",
            baseUrl = "http://localhost:11434/v1"
        )
        buildProviderFromProperties(props).name shouldBe "openai-compat"
    }

    test("buildProviderFromProperties throws when openai-compat is missing base-url") {
        val props = ProviderProperties(type = "openai-compat", model = "qwen2.5:7b")
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties throws when claude is missing an api key") {
        // buildProviderFromProperties now falls back to System.getenv("ANTHROPIC_API_KEY") when
        // props.apiKey is null (matching sophi-cli's ProviderFactory behavior). This test relies on
        // ANTHROPIC_API_KEY not being set to a real key in the test environment; asserting on the env
        // var directly would make the test flaky depending on who/where it runs, so instead we cover
        // the fallback structurally: the explicit-apiKey path is exercised by the "builds a claude
        // provider" test above, and this test documents that the null-apiKey path still throws when
        // no env var is present.
        val props = ProviderProperties(type = "claude", model = "claude-opus-4-8", apiKey = null)
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties throws for an unknown type") {
        val props = ProviderProperties(type = "bogus")
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties matches provider type case-insensitively") {
        val props = ProviderProperties(type = "Claude", model = "claude-opus-4-8", apiKey = "sk-ant-test")
        buildProviderFromProperties(props).name shouldBe "claude"
    }
})
