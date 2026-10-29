package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

private val FIXTURE = """
{
  "anthropic": {"id": "anthropic", "name": "Anthropic", "models": {
    "claude-x": {"id": "claude-x", "name": "Claude X", "tool_call": true, "reasoning": true,
      "modalities": {"input": ["text", "image"], "output": ["text"]},
      "limit": {"context": 200000, "output": 64000}, "cost": {"input": 3, "output": 15, "cache_read": 0.3}}
  }},
  "openrouter": {"id": "openrouter", "models": {
    "anthropic/claude-x": {"id": "anthropic/claude-x", "tool_call": true,
      "limit": {"context": 200000, "output": 64000}, "cost": {"input": 3.5, "output": 16}}
  }},
  "siliconflow": {"id": "siliconflow", "models": {
    "Qwen/Qwen3-8B": {"id": "Qwen/Qwen3-8B", "tool_call": false, "canonical_model_id": "alibaba/qwen3-8b",
      "limit": {"context": 131000, "output": 131000}, "cost": {"input": 0.1, "output": 0.2}}
  }},
  "thinkingmachines": {"id": "thinkingmachines", "models": {
    "Qwen/Qwen3-8B": {"id": "Qwen/Qwen3-8B", "tool_call": true, "reasoning": true, "canonical_model_id": "alibaba/qwen3-8b",
      "limit": {"context": 32768, "output": 32768}}
  }}
}
""".trimIndent()

class ModelCatalogTest : FunSpec({
    val catalog = ModelCatalog.parse(ModelCatalog.trim(FIXTURE))

    test("trim keeps only the compact fields") {
        val trimmed = ModelCatalog.trim(FIXTURE)
        trimmed.contains("Claude X") shouldBe false
        val c = catalog.find("claude-x", "anthropic")!!
        c.contextTokens shouldBe 200000
        c.outputTokens shouldBe 64000
        c.inputUsdPerM shouldBe 3.0
        c.cacheReadUsdPerM shouldBe 0.3
        (c.tools && c.reasoning && c.vision) shouldBe true
        c.priced shouldBe true
    }

    test("find prefers the provider asked for") {
        catalog.find("claude-x", "openrouter")!!.inputUsdPerM shouldBe 3.5
        catalog.find("claude-x", "anthropic")!!.inputUsdPerM shouldBe 3.0
        catalog.find("CLAUDE-X")!!.provider shouldBe "anthropic"
    }

    test("an unknown id is null and a vendor prefix falls back") {
        catalog.find("nope") shouldBe null
        catalog.find("anthropic/claude-x", "anthropic")!!.id shouldBe "claude-x"
    }

    test("a local GGUF id matches its base model") {
        for (id in listOf("qwen3-8b-q4_k_m", "Qwen3-8B-GGUF", "lmstudio-community/qwen3-8b@q4_k_m", "qwen3-8b.Q4_K_M.gguf", "qwen/qwen3-8b")) {
            val b = catalog.base(id) ?: error("no base for $id")
            (b.tools && b.reasoning) shouldBe true
            b.contextTokens shouldBe null
            b.inputUsdPerM shouldBe null
            b.priced shouldBe false
        }
        catalog.base("llama-9z") shouldBe null
    }

    test("a corrupt cache falls back to the snapshot") {
        val cache = tempdir().toPath().resolve("models.json")
        Files.writeString(cache, """{"anthropic":""")
        val warnings = mutableListOf<String>()
        val loaded = ModelCatalog.load(cache, null) { warnings += it }
        warnings shouldHaveSize 1
        warnings.single() shouldContain cache.toString()
        loaded.size() shouldBe ModelCatalog.bundled().size()
    }

    test("the user file wins") {
        val dir = tempdir().toPath()
        val cache = dir.resolve("models.json").also { Files.writeString(it, ModelCatalog.trim(FIXTURE)) }
        val user = dir.resolve("models.local.json").also { Files.writeString(it, """{"anthropic":{"claude-x":{"i":1,"p":2}}}""") }
        val loaded = ModelCatalog.load(cache, user) { error(it) }
        loaded.find("claude-x", "anthropic")!!.inputUsdPerM shouldBe 1.0
        loaded.find("claude-x", "openrouter")!!.inputUsdPerM shouldBe 3.5
    }

    test("refreshIfStale writes when missing or old, and not when fresh") {
        val cache = tempdir().toPath().resolve("sub/models.json")
        var calls = 0
        val fetch = { calls++; FIXTURE }
        ModelCatalog.refreshIfStale(cache, fetch = fetch) shouldBe true
        ModelCatalog.parse(Files.readString(cache)).find("claude-x")!!.contextTokens shouldBe 200000
        ModelCatalog.refreshIfStale(cache, fetch = fetch) shouldBe false
        calls shouldBe 1
        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minus(Duration.ofDays(2))))
        ModelCatalog.refreshIfStale(cache, fetch = fetch) shouldBe true
        calls shouldBe 2
        val before = Files.readString(cache)
        ModelCatalog.refreshIfStale(cache, maxAge = Duration.ZERO, fetch = { error("offline") }) shouldBe false
        Files.readString(cache) shouldBe before
    }
})
