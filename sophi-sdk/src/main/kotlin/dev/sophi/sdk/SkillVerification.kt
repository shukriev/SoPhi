package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.skills.Skill
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillRegistry
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

enum class SkillVerificationRecommendation { PROMOTE, REVERT, MANUAL_REVIEW }

data class SkillVerificationResult(val recommendation: SkillVerificationRecommendation, val reason: String)

private const val BORDERLINE_FRACTION_OF_CAP = 0.5

/**
 * Pure function, no LLM/IO — mirrors Tournament.evaluateAcceptance's regression-safety principle
 * but without its improvement requirement: a skill only affects behavior when the model chooses to
 * invoke it, so it doesn't need to move the suite's aggregate score to be worth keeping.
 */
fun recommendFromScores(
    baselineScores: Map<String, List<Double>>,
    candidateScores: Map<String, List<Double>>,
    regressionCap: Double = 0.02
): SkillVerificationResult {
    val regressed = regressedCategories(baselineScores, candidateScores, regressionCap)
    if (regressed.isNotEmpty()) {
        return SkillVerificationResult(SkillVerificationRecommendation.REVERT, "category '${regressed.first()}' regressed beyond the cap")
    }
    val borderline = regressedCategories(baselineScores, candidateScores, regressionCap * BORDERLINE_FRACTION_OF_CAP)
    if (borderline.isNotEmpty()) {
        return SkillVerificationResult(
            SkillVerificationRecommendation.MANUAL_REVIEW,
            "category '${borderline.first()}' regressed more than half the cap, short of triggering an automatic revert"
        )
    }
    return SkillVerificationResult(SkillVerificationRecommendation.PROMOTE, "no category regressed beyond half the cap")
}

data class SkillVerificationOutcome(val result: SkillVerificationResult, val coverageWarning: String?)

private class InvocationTrackingSkillTool(
    private val delegate: SkillTool,
    private val skillId: String,
    private val onInvoked: () -> Unit
) : Tool by delegate {
    override suspend fun execute(argumentsJson: String): String {
        if (argumentsJson.contains("\"name\":\"$skillId\"")) onInvoked()
        return delegate.execute(argumentsJson)
    }
}

private fun loadSkillFromContent(content: String): Skill {
    val dir = createTempDirectory("skill-verify-baseline")
    val file = dir.resolve("baseline.md")
    file.writeText(content)
    return SkillLoader().loadFile(file)
}

private data class SkillTracking(val skillId: String, val onInvoked: () -> Unit)

private fun registryWithSkillTool(base: ToolRegistry, skillRegistry: SkillRegistry, tracked: SkillTracking?): ToolRegistry {
    val registry = base.subset(base.names().filter { it != "skill" })
    val skillTool = SkillTool(skillRegistry)
    registry.register(if (tracked == null) skillTool else InvocationTrackingSkillTool(skillTool, tracked.skillId, tracked.onInvoked))
    return registry
}

/**
 * Runs the eval suite twice — once with [skillId]'s previous version applied (or absent entirely
 * if this is its first version), once with the current on-disk (trial) content — and recommends
 * promote/revert/manual-review via [recommendFromScores]. Also re-runs the same static checks
 * [WriteSkillTool]/[InstallSkillTool] already ran at write time, retroactively — catches a version
 * written before this phase shipped, when no check existed yet. [SkillVerificationOutcome
 * .coverageWarning] is non-null when no eval case invoked this skill during the candidate run,
 * meaning the result can't actually speak to this skill's own quality.
 */
suspend fun verifySkill(
    skillId: String,
    project: Boolean,
    globalSkillsHome: Path,
    projectSkillsHome: Path,
    cases: List<EvalCase>,
    provider: LLMProvider,
    baseRegistry: ToolRegistry,
    sessionManager: SessionManager,
    contextWindowTokens: Int,
    model: String,
    scorecardStore: ScorecardStore,
    runsPerConfig: Int = 3
): SkillVerificationOutcome {
    val skillsHome = if (project) projectSkillsHome else globalSkillsHome
    val versionStore = SkillVersionStore(VersionStore(skillsHome.resolve(".versions")), project)
    val history = versionStore.history(skillId, project) // newest first
    val current = history.firstOrNull() ?: error("no version history for skill '$skillId'")

    val staticViolations = checkInstalledSkillContent(current.content)
    if (staticViolations.isNotEmpty()) {
        return SkillVerificationOutcome(
            SkillVerificationResult(SkillVerificationRecommendation.REVERT, "fails static content checks: ${staticViolations.joinToString("; ")}"),
            coverageWarning = null
        )
    }

    val previous = history.getOrNull(1)
    val candidateSkillRegistry = SkillRegistry.load(globalSkillsHome, projectSkillsHome)
    val baselineSkills = candidateSkillRegistry.all().toMap().toMutableMap()
    if (previous == null) baselineSkills.remove(skillId) else baselineSkills[skillId] = loadSkillFromContent(previous.content)
    val baselineSkillRegistry = SkillRegistry(baselineSkills)

    var invocationCount = 0
    val baselineScores = mutableMapOf<String, MutableList<Double>>()
    val candidateScores = mutableMapOf<String, MutableList<Double>>()
    repeat(runsPerConfig) {
        val baselineScorecard = runSuite(
            cases, provider, registryWithSkillTool(baseRegistry, baselineSkillRegistry, tracked = null),
            sessionManager, contextWindowTokens, model, "$skillId-baseline", systemPrompt = null, scorecardStore
        )
        baselineScores.getOrPut("overall") { mutableListOf() }.add(baselineScorecard.headlineScore)
        baselineScorecard.perCategory.forEach { (cat, score) -> baselineScores.getOrPut(cat) { mutableListOf() }.add(score) }

        val candidateScorecard = runSuite(
            cases, provider, registryWithSkillTool(baseRegistry, candidateSkillRegistry, tracked = SkillTracking(skillId) { invocationCount++ }),
            sessionManager, contextWindowTokens, model, current.id, systemPrompt = null, scorecardStore
        )
        candidateScores.getOrPut("overall") { mutableListOf() }.add(candidateScorecard.headlineScore)
        candidateScorecard.perCategory.forEach { (cat, score) -> candidateScores.getOrPut(cat) { mutableListOf() }.add(score) }
    }

    val result = recommendFromScores(baselineScores, candidateScores)
    val coverageWarning = if (invocationCount == 0) {
        "no eval case invoked skill '$skillId' during verification — this result can't confirm the skill's own quality, only that its presence didn't break anything else"
    } else null
    return SkillVerificationOutcome(result, coverageWarning)
}
