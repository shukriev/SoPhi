package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.EvalScenario
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class TournamentKillSwitchTest : FunSpec({
    test("runTournament refuses to run when the kill switch is not explicitly enabled") {
        val versionStore = VersionStore(tempdir().toPath())
        val incumbentVersion = versionStore.record(
            ArtifactType.CONFIG, "default",
            Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), HarnessConfig()), ProducedBy.HUMAN
        )

        shouldThrow<IllegalStateException> {
            runBlocking {
                runTournament(
                    incumbentVersionId = incumbentVersion.id, versionStore = versionStore,
                    scorecardStore = ScorecardStore(tempdir().toPath()), cases = emptyList(),
                    provider = mockk<LLMProvider>(), registry = ToolRegistry(),
                    sessionManager = FileSessionManager(createTempDirectory("kill-switch-test")),
                    contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
                    unaddressedFailureModes = emptyList(), toolStats = emptyMap(),
                    env = { null }
                )
            }
        }
    }

    test("runTournament refuses to run when the kill switch env var is explicitly false") {
        val versionStore = VersionStore(tempdir().toPath())
        val incumbentVersion = versionStore.record(
            ArtifactType.CONFIG, "default",
            Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), HarnessConfig()), ProducedBy.HUMAN
        )

        shouldThrow<IllegalStateException> {
            runBlocking {
                runTournament(
                    incumbentVersionId = incumbentVersion.id, versionStore = versionStore,
                    scorecardStore = ScorecardStore(tempdir().toPath()), cases = emptyList(),
                    provider = mockk<LLMProvider>(), registry = ToolRegistry(),
                    sessionManager = FileSessionManager(createTempDirectory("kill-switch-test")),
                    contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
                    unaddressedFailureModes = emptyList(), toolStats = emptyMap(),
                    env = { "false" }
                )
            }
        }
    }

    test("runTournament proceeds when the kill switch env var is explicitly true") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val challenger = HarnessConfig(systemPrompt = "Challenger")
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text(Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), challenger), TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        val versionStore = VersionStore(tempdir().toPath())
        val incumbentVersion = versionStore.record(
            ArtifactType.CONFIG, "default",
            Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), HarnessConfig(systemPrompt = "Incumbent")), ProducedBy.HUMAN
        )
        val cases = listOf(EvalCase("c1", "cat", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1)))

        val result = runBlocking {
            runTournament(
                incumbentVersionId = incumbentVersion.id, versionStore = versionStore,
                scorecardStore = ScorecardStore(tempdir().toPath()), cases = cases,
                provider = provider, registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("kill-switch-test")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
                unaddressedFailureModes = emptyList(), toolStats = emptyMap(), runsPerConfig = 1,
                env = { "true" }
            )
        }

        result shouldNotBe null
    }
})
