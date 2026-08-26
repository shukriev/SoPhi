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
private val testJson = Json { encodeDefaults = true }

class TournamentRunnerTest : FunSpec({
    test("runTournament proposes a challenger, records it, runs both configs through the suite, and returns a TournamentResult") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val challenger = HarnessConfig(systemPrompt = "Challenger prompt")
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text(testJson.encodeToString(HarnessConfig.serializer(), challenger), TokenUsage(1, 1)), // 1: mutation proposal
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)), // 2: incumbent plan-gen
            LLMResponse.Text("1.0", TokenUsage(1, 1)), // 3: incumbent critic
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)), // 4: challenger plan-gen
            LLMResponse.Text("1.0", TokenUsage(1, 1)) // 5: challenger critic
        )
        val versionStore = VersionStore(tempdir().toPath())
        val scorecardStore = ScorecardStore(tempdir().toPath())
        val incumbent = HarnessConfig(systemPrompt = "Incumbent prompt")
        val incumbentVersion = versionStore.record(
            ArtifactType.CONFIG, "default", testJson.encodeToString(HarnessConfig.serializer(), incumbent), ProducedBy.HUMAN
        )
        val cases = listOf(EvalCase("c1", "cat", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1)))

        val result = runBlocking {
            runTournament(
                incumbentVersionId = incumbentVersion.id, versionStore = versionStore, scorecardStore = scorecardStore,
                cases = cases, provider = provider, registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("tournament-test")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
                unaddressedFailureModes = emptyList(), toolStats = emptyMap(), runsPerConfig = 1
            )
        }

        val recordedChallenger = versionStore.get(result.challengerVersionId)
        recordedChallenger shouldNotBe null
        recordedChallenger?.producedBy shouldNotBe ProducedBy.HUMAN
        result.result shouldNotBe null
    }
})
