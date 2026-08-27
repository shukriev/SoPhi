package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TEST_CONTEXT_WINDOW = 100_000

private class FakeTool(override val name: String, override val description: String) : Tool {
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
    override suspend fun execute(argumentsJson: String): String = "done"
}

class RuntimeBuilderToolDescriptionOverrideTest : FunSpec({
    test("a HarnessConfig's toolDescriptionOverrides replaces a named tool's description at build time") {
        val versionStore = VersionStore(tempdir().toPath())
        val config = HarnessConfig(toolDescriptionOverrides = mapOf("greet" to "A rewritten description"))
        val json = Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), config)
        val version = versionStore.record(ArtifactType.CONFIG, "default", json, ProducedBy.HUMAN)
        val registry = ToolRegistry().register(FakeTool("greet", "original description"))

        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).toolRegistry(registry).configVersion(version.id, versionStore).build()

        runtime.toolNames() shouldBe listOf("greet")
        registry.get("greet").description shouldBe "A rewritten description"
    }

    test("execute() still delegates to the original tool after a description override") {
        val versionStore = VersionStore(tempdir().toPath())
        val config = HarnessConfig(toolDescriptionOverrides = mapOf("greet" to "overridden"))
        val json = Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), config)
        val version = versionStore.record(ArtifactType.CONFIG, "default", json, ProducedBy.HUMAN)
        val registry = ToolRegistry().register(FakeTool("greet", "original description"))
        RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).toolRegistry(registry).configVersion(version.id, versionStore).build()

        val result = kotlinx.coroutines.runBlocking { registry.get("greet").execute("{}") }

        result shouldBe "done"
    }

    test("an override naming a tool that isn't registered is silently ignored") {
        val versionStore = VersionStore(tempdir().toPath())
        val config = HarnessConfig(toolDescriptionOverrides = mapOf("does-not-exist" to "overridden"))
        val json = Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), config)
        val version = versionStore.record(ArtifactType.CONFIG, "default", json, ProducedBy.HUMAN)
        val registry = ToolRegistry().register(FakeTool("greet", "original description"))

        RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).toolRegistry(registry).configVersion(version.id, versionStore).build()

        registry.get("greet").description shouldBe "original description"
    }
})
