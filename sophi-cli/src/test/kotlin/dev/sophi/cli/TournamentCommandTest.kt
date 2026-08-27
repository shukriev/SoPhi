package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.EvalScenario
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.sdk.HarnessConfig
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000
private val json = Json { encodeDefaults = true }

class TournamentCommandTest : FunSpec({
    test("TournamentStatusReport prints challengers-proposed and promotions-accepted counts") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        versionStore.record(ArtifactType.CONFIG, "default", "{}", ProducedBy.TOURNAMENT)
        val promoted = versionStore.record(ArtifactType.CONFIG, "default", "{}", ProducedBy.TOURNAMENT)
        dev.sophi.sdk.activateConfigVersion(versionStore, promoted.id, "promoted")
        val lines = mutableListOf<String>()

        TournamentStatusReport(versionStore) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "challengersProposed=2"
        lines.joinToString("\n") shouldContain "promotionsAccepted=1"
    }

    test("ConfigActivate points the config artifact at the target version's content and reports success") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val v1 = versionStore.record(ArtifactType.CONFIG, "default", "original content", ProducedBy.HUMAN)
        versionStore.record(ArtifactType.CONFIG, "default", "newer content", ProducedBy.HUMAN)
        val lines = mutableListOf<String>()

        ConfigActivate(versionStore, v1.id) { lines.add(it) }.run()

        versionStore.history(ArtifactType.CONFIG, "default") shouldHaveSize 3
        lines.first() shouldContain "activated"
    }

    test("ConfigActivate reports failure for an unknown version id") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val lines = mutableListOf<String>()

        ConfigActivate(versionStore, "does-not-exist") { lines.add(it) }.run()

        lines.first() shouldContain "No version found"
    }

    test("TournamentPromote does nothing when the confirmation is declined") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val challenger = versionStore.record(ArtifactType.CONFIG, "default", "content", ProducedBy.TOURNAMENT)
        val lines = mutableListOf<String>()

        TournamentPromote(versionStore, challenger.id, confirm = { false }) { lines.add(it) }.run()

        versionStore.history(ArtifactType.CONFIG, "default") shouldHaveSize 1
        lines.first() shouldContain "cancelled"
    }

    test("TournamentPromote activates the version and marks it promoted when confirmed") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val challenger = versionStore.record(ArtifactType.CONFIG, "default", "content", ProducedBy.TOURNAMENT)
        val lines = mutableListOf<String>()

        TournamentPromote(versionStore, challenger.id, confirm = { true }) { lines.add(it) }.run()

        val history = versionStore.history(ArtifactType.CONFIG, "default")
        history shouldHaveSize 2
        history.last().note shouldContain "promoted"
        lines.first() shouldContain "promoted"
    }

    test("TournamentRun runs a tournament and prints the challenger version id and result") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val challenger = HarnessConfig(systemPrompt = "Challenger")
        io.mockk.coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text(json.encodeToString(HarnessConfig.serializer(), challenger), TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val scorecardStore = ScorecardStore(createTempDirectory("tournament-cli-scores"))
        val incumbent = HarnessConfig(systemPrompt = "Incumbent")
        val incumbentVersion = versionStore.record(ArtifactType.CONFIG, "default", json.encodeToString(HarnessConfig.serializer(), incumbent), ProducedBy.HUMAN)
        val cases = listOf(EvalCase("c1", "cat", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1)))
        val lines = mutableListOf<String>()

        TournamentRun(
            incumbentVersionId = incumbentVersion.id, versionStore = versionStore, scorecardStore = scorecardStore,
            cases = cases, provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("tournament-cli-sessions")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", runsPerConfig = 1,
            env = { "true" }
        ) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "challenger version:"
        lines.joinToString("\n") shouldContain "accepted="
    }

    test("TournamentRun reports a clear message when the kill switch is disabled, without crashing") {
        val versionStore = VersionStore(createTempDirectory("tournament-cli-test"))
        val incumbentVersion = versionStore.record(ArtifactType.CONFIG, "default", json.encodeToString(HarnessConfig.serializer(), HarnessConfig()), ProducedBy.HUMAN)
        val cases = listOf(EvalCase("c1", "cat", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1)))
        val lines = mutableListOf<String>()

        TournamentRun(
            incumbentVersionId = incumbentVersion.id, versionStore = versionStore,
            scorecardStore = ScorecardStore(createTempDirectory("tournament-cli-scores")), cases = cases,
            provider = mockk<LLMProvider>(), registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("tournament-cli-sessions")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", runsPerConfig = 1,
            env = { null }
        ) { lines.add(it) }.run()

        lines.first() shouldContain "disabled"
    }
})
