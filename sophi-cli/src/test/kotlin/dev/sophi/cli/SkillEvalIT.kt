package dev.sophi.cli

import dev.sophi.ai.providers.buildProviderFromType
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.eval.EvalScenario
import dev.sophi.core.agent.eval.runEvalScenario
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.skills.SkillRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

private const val EVAL_CONTEXT_WINDOW = 100_000
private const val MARKER = "SOPHI-EVAL-42"
private const val EVAL_MODEL = "claude-3-5-haiku-20241022"

/**
 * The real, non-mocked before/after evaluation scenario the eval-reversibility-substrate spec
 * calls for: writes an answer file the model can only produce by discovering and following a
 * freshly-authored skill. Requires ANTHROPIC_API_KEY — skipped, not failed, when it's absent, and
 * only runs at all under `mvn verify -DskipITs=false` (never under plain `mvn test`).
 */
class SkillEvalIT : FunSpec({
    fun scenario(workspace: Path) = EvalScenario(
        name = "skill-eval-answer-challenge",
        goalPrompt = "Find a skill about \"the answer file challenge\" and follow its instructions exactly.",
        check = StopCondition.ShellCheck(
            command = "test -f $workspace/answer.txt && grep -q $MARKER $workspace/answer.txt"
        )
    )

    test("a scenario the model cannot pass without a skill passes once WriteSkillTool records that skill")
        .config(enabled = System.getenv("ANTHROPIC_API_KEY") != null) {

        val provider = buildProviderFromType(type = "claude", apiKey = null, baseUrl = null, model = EVAL_MODEL)
        val skillsDir = createTempDirectory("skill-eval-skills")
        val emptyProjectSkillsDir = createTempDirectory("skill-eval-empty-project")
        val workspace = createTempDirectory("skill-eval-workspace")

        val beforeRegistry = ToolRegistry()
            .register(SkillTool(SkillRegistry.load(skillsDir, emptyProjectSkillsDir)))
            .register(FileWriteTool(root = workspace))
        val before = runEvalScenario(
            provider = provider, registry = beforeRegistry,
            sessionManager = FileSessionManager(createTempDirectory("skill-eval-sessions-before")),
            contextWindowTokens = EVAL_CONTEXT_WINDOW, model = EVAL_MODEL,
            scenario = scenario(workspace)
        )
        before.finalStatus shouldBe PlanFinalStatus.Exhausted

        val writeSkillRegistry = ToolRegistry().register(WriteSkillTool { skillsDir })
        val writeSessionManager = FileSessionManager(createTempDirectory("skill-eval-sessions-write"))
        val writer = AgentLoop(
            provider, writeSkillRegistry, writeSessionManager,
            confirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
            contextWindowTokens = EVAL_CONTEXT_WINDOW
        )
        writer.turn(
            session = writeSessionManager.create(),
            userInput = "Use write_skill to record a skill with id site-eval-answer-challenge, " +
                "title \"the answer file challenge\", describing that following it requires writing " +
                "a file named answer.txt in the current working directory containing the exact line $MARKER.",
            config = AgentConfig(model = EVAL_MODEL)
        )

        val afterRegistry = ToolRegistry()
            .register(SkillTool(SkillRegistry.load(skillsDir, emptyProjectSkillsDir)))
            .register(FileWriteTool(root = workspace))
        val after = runEvalScenario(
            provider = provider, registry = afterRegistry,
            sessionManager = FileSessionManager(createTempDirectory("skill-eval-sessions-after")),
            contextWindowTokens = EVAL_CONTEXT_WINDOW, model = EVAL_MODEL,
            scenario = scenario(workspace)
        )
        after.finalStatus shouldBe PlanFinalStatus.Met
    }
})
