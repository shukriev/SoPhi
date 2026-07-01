package dev.sophi.ai.providers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
})
