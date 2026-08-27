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
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class SkillVerificationRunnerTest : FunSpec({
    test("a skill with no eval-case coverage returns a non-null coverageWarning") {
        val globalSkillsHome = createTempDirectory("verify-global")
        globalSkillsHome.resolve("site-x.md").writeText("---\ntitle: x\ndescription: d\n---\nbody")
        SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .record(SkillVersion(skillId = "site-x", project = false, content = "---\ntitle: x\ndescription: d\n---\nbody", trial = true))
        val projectSkillsHome = createTempDirectory("verify-project")

        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        val cases = listOf(EvalCase("c1", "cat", EvalScenario("c1", "goal that never mentions the skill", StopCondition.ShellCheck("exit 0"), maxIterations = 1)))

        val outcome = runBlocking {
            verifySkill(
                "site-x", project = false, globalSkillsHome, projectSkillsHome, cases, provider,
                ToolRegistry(), FileSessionManager(createTempDirectory("verify-sessions")),
                TEST_CONTEXT_WINDOW, "test-model", ScorecardStore(createTempDirectory("verify-scores")), runsPerConfig = 1
            )
        }

        outcome.coverageWarning shouldBe "no eval case invoked skill 'site-x' during verification — this result can't confirm the skill's own quality, only that its presence didn't break anything else"
    }

    test("a skill version failing the retroactive static check recommends REVERT without running any eval case") {
        val globalSkillsHome = createTempDirectory("verify-global")
        globalSkillsHome.resolve("site-y.md").writeText("---\ntitle: y\ndescription: d\n---\ntoken: AKIAABCDEFGHIJKLMNOP")
        SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .record(SkillVersion(skillId = "site-y", project = false, content = "---\ntitle: y\ndescription: d\n---\ntoken: AKIAABCDEFGHIJKLMNOP", trial = true))
        val projectSkillsHome = createTempDirectory("verify-project")

        val outcome = runBlocking {
            verifySkill(
                "site-y", project = false, globalSkillsHome, projectSkillsHome, emptyList(), mockk(),
                ToolRegistry(), FileSessionManager(createTempDirectory("verify-sessions")),
                TEST_CONTEXT_WINDOW, "test-model", ScorecardStore(createTempDirectory("verify-scores"))
            )
        }

        outcome.result.recommendation shouldBe SkillVerificationRecommendation.REVERT
        outcome.result.reason shouldBe "fails static content checks: content matches a secret/credential pattern (AKIA[0-9A-Z]{16})"
    }
})
