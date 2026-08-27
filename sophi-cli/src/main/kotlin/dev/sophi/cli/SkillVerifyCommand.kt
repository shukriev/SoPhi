package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.loadEvalCases
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.sdk.SkillVerificationRecommendation
import dev.sophi.sdk.verifySkill
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private const val SKILL_VERIFY_CONTEXT_WINDOW = 100_000

class SkillVerify(
    private val skillIds: List<String>,
    private val project: Boolean,
    private val globalSkillsHome: Path,
    private val projectSkillsHome: Path,
    private val cases: List<EvalCase>,
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val contextWindowTokens: Int,
    private val model: String,
    private val scorecardStore: ScorecardStore,
    private val confirm: (String) -> Boolean,
    private val echo: (String) -> Unit
) {
    fun run() = skillIds.forEach(::runOne)

    private fun runOne(skillId: String) {
        val skillsHome = if (project) projectSkillsHome else globalSkillsHome
        val versionStore = VersionStore(skillsHome.resolve(".versions"))
        val skillVersionStore = SkillVersionStore(versionStore, project)

        val outcome = runCatching {
            runBlocking {
                verifySkill(
                    skillId, project, globalSkillsHome, projectSkillsHome, cases, provider,
                    registry, sessionManager, contextWindowTokens, model, scorecardStore
                )
            }
        }.getOrElse { e -> echo("$skillId: ${e.message}"); return }

        echo("$skillId: ${outcome.result.recommendation} -- ${outcome.result.reason}")
        outcome.coverageWarning?.let { echo("$skillId: WARNING: $it") }

        when (outcome.result.recommendation) {
            SkillVerificationRecommendation.MANUAL_REVIEW -> Unit
            SkillVerificationRecommendation.PROMOTE -> {
                if (!confirm("Promote '$skillId' out of trial? [y/N] ")) { echo("$skillId: cancelled"); return }
                val current = skillVersionStore.history(skillId, project).first()
                skillVersionStore.record(SkillVersion(skillId = skillId, project = project, content = current.content, trial = false))
                echo("$skillId: promoted")
            }
            SkillVerificationRecommendation.REVERT -> {
                if (!confirm("Revert '$skillId'? [y/N] ")) { echo("$skillId: cancelled"); return }
                val previous = skillVersionStore.history(skillId, project).getOrNull(1)
                if (previous == null) { echo("$skillId: cannot revert -- no earlier version exists"); return }
                versionStore.revert(ArtifactType.SKILL, skillId, previous.id)
                echo("$skillId: reverted to ${previous.id}")
            }
        }
    }
}

class SkillVerifyCommand : CliktCommand(name = "verify", help = "Verify a trial skill version against the eval suite and report a promote/revert recommendation") {
    private val id: String? by argument().optional()
    private val all: Boolean by option("--all").flag()
    private val project: Boolean by option("--project").flag()
    private val confirmFlag: Boolean by option("--confirm").flag()
    private val evalsDirStr: String by option("--evals-dir").default("evals")
    private val category: String? by option("--category")
    private val model: String by option("--model", "-m").default("claude-3-5-sonnet-20241022")
    private val providerType: String by option("--provider").default("claude")
    private val baseUrl: String? by option("--base-url")
    private val apiKeyOption: String? by option("--api-key")
    private val sessionsDirStr: String by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")
    private val versioningHomeStr: String by option("--versioning-home")
        .default("${System.getProperty("user.home")}/.sophi/versioning")

    override fun run() {
        val globalSkillsHome = Path.of(System.getProperty("user.home"), ".sophi", "skills")
        val projectSkillsHome = Path.of(".sophi", "skills")
        val skillsHome = if (project) projectSkillsHome else globalSkillsHome
        val targetIds = when {
            all -> SkillVersionStore(VersionStore(skillsHome.resolve(".versions")), project)
                .all().filter { it.trial }.map { it.skillId }.distinct()
            id != null -> listOf(id!!)
            else -> { echo("Specify a skill id or --all"); return }
        }
        if (targetIds.isEmpty()) { echo("No trial skill versions found."); return }

        val allCases = loadEvalCases(Path.of(evalsDirStr))
        val cases = if (category == null) allCases else allCases.filter { it.category == category }
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
        SkillVerify(
            skillIds = targetIds, project = project, globalSkillsHome = globalSkillsHome, projectSkillsHome = projectSkillsHome,
            cases = cases, provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(Path.of(sessionsDirStr)), contextWindowTokens = SKILL_VERIFY_CONTEXT_WINDOW,
            model = model, scorecardStore = ScorecardStore(Path.of(versioningHomeStr)),
            confirm = { prompt -> confirmFlag && run { print(prompt); readlnOrNull()?.trim()?.lowercase() == "y" } }
        ) { echo(it) }.run()
    }
}
