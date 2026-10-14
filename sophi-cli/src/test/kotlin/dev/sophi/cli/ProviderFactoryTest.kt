package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.system1.DualSystemProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

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

    test("buildProvider matches provider type case-insensitively") {
        val provider = buildProvider("Claude", "sk-ant-test", null, "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("buildProvider threads a custom requestTimeoutSeconds into the openai-compat provider") {
        val provider = buildProvider(
            "openai-compat", null, "http://localhost:11434/v1", "qwen2.5:7b",
            requestTimeoutSeconds = 300)
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider threads a custom maxRetries into the openai-compat provider") {
        val provider = buildProvider(
            "openai-compat", null, "http://localhost:11434/v1", "qwen2.5:7b",
            maxRetries = 0)
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider wraps with System 1 when sophiHome has a system1.json") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://localhost:1234/v1","model":"small"}""")
        val provider = buildProvider("openai-compat", null, "http://localhost:11434/v1", "qwen2.5:7b", sophiHome = home)
        provider.shouldBeInstanceOf<DualSystemProvider>()
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider without a system1.json returns the plain provider") {
        val provider = buildProvider("openai-compat", null, "http://localhost:11434/v1", "qwen2.5:7b", sophiHome = tempdir().toPath())
        (provider is DualSystemProvider) shouldBe false
    }
})
