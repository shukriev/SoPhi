package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class SkillVerifyCommandTest : FunSpec({
    test("SkillVerify reports a REVERT recommendation and does not mutate history without confirmation") {
        val globalSkillsHome = createTempDirectory("verify-cli-global")
        globalSkillsHome.resolve("site-z.md").writeText("---\ntitle: z\ndescription: d\n---\ntoken: AKIAABCDEFGHIJKLMNOP")
        SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .record(SkillVersion(skillId = "site-z", project = false, content = "---\ntitle: z\ndescription: d\n---\ntoken: AKIAABCDEFGHIJKLMNOP", trial = true))
        val lines = mutableListOf<String>()

        SkillVerify(
            skillIds = listOf("site-z"), project = false, globalSkillsHome = globalSkillsHome,
            projectSkillsHome = createTempDirectory("verify-cli-project"), cases = emptyList<EvalCase>(),
            provider = mockk<LLMProvider>(), registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("verify-cli-sessions")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
            scorecardStore = ScorecardStore(createTempDirectory("verify-cli-scores")),
            confirm = { false }
        ) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "REVERT"
        SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .history("site-z", project = false) shouldHaveSize 1
    }

    test("SkillVerify promotes a passing skill when confirmed, recording a new trial=false version") {
        val globalSkillsHome = createTempDirectory("verify-cli-global")
        val content = "---\ntitle: ok\ndescription: d\n---\nbody"
        globalSkillsHome.resolve("site-ok.md").writeText(content)
        SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .record(SkillVersion(skillId = "site-ok", project = false, content = content, trial = true))
        val lines = mutableListOf<String>()

        SkillVerify(
            skillIds = listOf("site-ok"), project = false, globalSkillsHome = globalSkillsHome,
            projectSkillsHome = createTempDirectory("verify-cli-project"), cases = emptyList<EvalCase>(),
            provider = mockk<LLMProvider>(), registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("verify-cli-sessions")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
            scorecardStore = ScorecardStore(createTempDirectory("verify-cli-scores")),
            confirm = { true }
        ) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "promoted"
        val history = SkillVersionStore(VersionStore(globalSkillsHome.resolve(".versions")), project = false)
            .history("site-ok", project = false)
        history shouldHaveSize 2
        history.first().trial shouldBe false
    }
})
