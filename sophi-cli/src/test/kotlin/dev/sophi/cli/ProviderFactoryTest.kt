package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.system1.DualSystemProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

private fun rp(type: String, baseUrl: String?, model: String) =
    ResolvedProvider(type, baseUrl, model, 8192, null, emptyList())

class ProviderFactoryTest : FunSpec({
    test("buildProvider returns claude provider when apiKeyOverride is given") {
        val provider = buildProvider(rp("claude", null, "claude-opus-4-8"), "sk-ant-test")
        provider.name shouldBe "claude"
    }

    test("buildProvider returns openai-compat provider for valid flags") {
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null)
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider throws UsageError when openai-compat has no base-url") {
        shouldThrow<UsageError> { buildProvider(rp("openai-compat", null, "qwen2.5:7b"), null) }
    }

    test("buildProvider throws UsageError for an unknown provider type") {
        shouldThrow<UsageError> { buildProvider(rp("bogus", null, "some-model"), null) }
    }

    test("buildProvider matches provider type case-insensitively") {
        val provider = buildProvider(rp("Claude", null, "claude-opus-4-8"), "sk-ant-test")
        provider.name shouldBe "claude"
    }

    test("buildProvider threads a custom requestTimeoutSeconds into the openai-compat provider") {
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null,
            requestTimeoutSeconds = 300)
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider threads a custom maxRetries into the openai-compat provider") {
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null,
            maxRetries = 0)
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider wraps with System 1 when sophiHome has a system1.json") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://localhost:1234/v1","model":"small"}""")
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null, sophiHome = home)
        provider.shouldBeInstanceOf<DualSystemProvider>()
        provider.name shouldBe "openai-compat"
    }

    test("buildProvider without a system1.json returns the plain provider") {
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null, sophiHome = tempdir().toPath())
        (provider is DualSystemProvider) shouldBe false
    }

    test("the meter goes inside System 1, so a judgment the local student serves is never billed") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://localhost:1234/v1","model":"small"}""")
        var wrapped: dev.sophi.ai.api.LLMProvider? = null
        val provider = buildProvider(rp("openai-compat", "http://localhost:11434/v1", "qwen2.5:7b"), null, sophiHome = home,
            inner = { p -> wrapped = p; p })
        provider.shouldBeInstanceOf<DualSystemProvider>()
        (wrapped is DualSystemProvider) shouldBe false
        wrapped!!.name shouldBe "openai-compat"
    }
})
