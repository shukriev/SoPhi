package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProviderFactoryTest : FunSpec({
    test("buildProvider returns claude provider when apiKeyOverride is given") {
        val provider = buildProvider("claude", "sk-ant-test", null, "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("buildProvider returns openai-compat provider for valid flags") {
        val provider = buildProvider("openai-compat", null, "http://localhost:11434/v1", "qwen2.5:7b")
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider throws UsageError when openai-compat has no base-url") {
        shouldThrow<UsageError> { buildProvider("openai-compat", null, null, "qwen2.5:7b") }
    }

    test("buildProvider throws UsageError for an unknown provider type") {
        shouldThrow<UsageError> { buildProvider("bogus", null, null, "some-model") }
    }
})
