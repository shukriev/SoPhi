package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.tools.RiskLevel
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

class TournamentToolTest : FunSpec({
    test("riskLevel is always SAFE") {
        val tool = TournamentTool(
            provider = mockk<LLMProvider>(), model = "m", contextWindowTokens = 100_000,
            sessionsDir = createTempDirectory("tournament-tool-test-sessions"),
            versioningHome = createTempDirectory("tournament-tool-test-versioning")
        )

        tool.riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("execute() reports a readable failure when no default config version has been seeded") {
        val versioningHome = createTempDirectory("tournament-tool-test-versioning")
        val tool = TournamentTool(
            provider = mockk<LLMProvider>(), model = "m", contextWindowTokens = 100_000,
            sessionsDir = createTempDirectory("tournament-tool-test-sessions"),
            versioningHome = versioningHome
        )

        val result = kotlinx.coroutines.runBlocking { tool.execute("{}") }

        result shouldContain "No config version found for 'default'"
    }

    test("execute() reports a readable failure when no eval cases exist") {
        val versioningHome = createTempDirectory("tournament-tool-test-versioning")
        VersionStore(versioningHome).record(ArtifactType.CONFIG, "default", "{}", ProducedBy.HUMAN)
        val emptyEvalsDir = createTempDirectory("tournament-tool-test-evals-empty")
        val tool = TournamentTool(
            provider = mockk<LLMProvider>(), model = "m", contextWindowTokens = 100_000,
            sessionsDir = createTempDirectory("tournament-tool-test-sessions"),
            versioningHome = versioningHome, evalsDir = emptyEvalsDir
        )

        val result = kotlinx.coroutines.runBlocking { tool.execute("{}") }

        result shouldContain "No eval cases found"
    }
})
