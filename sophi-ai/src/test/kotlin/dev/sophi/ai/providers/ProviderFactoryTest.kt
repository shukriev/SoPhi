package dev.sophi.ai.providers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class ProviderFactoryTest : FunSpec({
    test("buildClaudeProvider returns a provider named 'claude'") {
        val provider = buildClaudeProvider(apiKey = "sk-ant-test", model = "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("buildOpenAiCompatProvider returns a provider with the given name") {
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            name = "ollama"
        )
        provider.name shouldBe "ollama"
    }

    test("buildOpenAiCompatProvider defaults name to 'openai-compat'") {
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:8000/v1",
            apiKey = "sk-test",
            model = "Qwen/Qwen2.5-7B-Instruct"
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildOpenAiCompatProvider accepts a custom requestTimeout") {
        // Local/reasoning models can take well over the 60s default to finish a completion;
        // this must be overridable rather than a fixed client-side timeout that aborts the
        // request out from under a model that's still generating.
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            requestTimeout = Duration.ofSeconds(300)
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildOpenAiCompatProvider accepts a custom maxRetries") {
        // The OpenAI Java SDK retries maxRetries times, each subject to the full
        // requestTimeout — so effective worst-case latency is requestTimeout * (maxRetries+1).
        // A slow model that's consistently near the timeout should be able to opt out of
        // retries entirely rather than silently waiting multiples of the configured timeout.
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            maxRetries = 0
        )
        provider.name shouldBe "openai-compat"
    }
})
