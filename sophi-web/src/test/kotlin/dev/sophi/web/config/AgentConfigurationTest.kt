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
        val props = ProviderProperties(type = "claude", model = "claude-opus-4-8", apiKey = null)
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties throws for an unknown type") {
        val props = ProviderProperties(type = "bogus")
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }
})
