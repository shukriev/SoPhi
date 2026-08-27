package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderConfigVersionTest : FunSpec({
    test("configVersion() applies a stored HarnessConfig's reachable fields") {
        val versionStore = VersionStore(tempdir().toPath())
        val config = HarnessConfig(systemPrompt = "Custom prompt", temperature = 0.3, maxTokens = 2048, maxRecalledLessons = 5)
        val json = Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), config)
        val version = versionStore.record(ArtifactType.CONFIG, "default", json, ProducedBy.HUMAN)

        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).configVersion(version.id, versionStore).build()

        runtime.config.systemPrompt shouldContain "Custom prompt"
        runtime.config.maxTokens shouldBe 2048
        runtime.config.temperature shouldBe 0.3
    }

    test("omitting configVersion() preserves today's exact default behavior") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        runtime.config.maxTokens shouldBe 4096
        runtime.config.temperature shouldBe 0.7
    }

    test("configVersion() with an unknown version id leaves the builder's own fields in effect") {
        val versionStore = VersionStore(tempdir().toPath())

        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
            maxTokens = 1234
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).configVersion("does-not-exist", versionStore).build()

        runtime.config.maxTokens shouldBe 1234
    }
})
