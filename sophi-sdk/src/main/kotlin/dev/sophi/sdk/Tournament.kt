package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.learning.ToolStats
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.Version
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

private val tournamentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Fails toward OFF — a still-on-probation mutation mechanism must be explicitly opted into, the
 *  same direction as `SOPHI_ORCHESTRATOR_ENABLED` (ADR-027), not `SOPHI_TOT_SEARCH_ENABLED`'s
 *  fail-toward-ON. [env] is injectable for testing; System.getenv cannot be mutated in-process. */
const val TOURNAMENT_ENABLED_ENV = "SOPHI_TOURNAMENT_ENABLED"

/** A challenger scoring more than this far above the incumbent is flagged suspect regardless of
 *  statistical significance — the suite/grader is part of what can be gamed. */
private const val REWARD_HACKING_JUMP_THRESHOLD = 0.10

data class TournamentResult(val accepted: Boolean, val reason: String, val requiresManualReview: Boolean)

private fun mean(xs: List<Double>): Double = xs.average()

private fun stddev(xs: List<Double>): Double {
    val m = mean(xs)
    return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
}

/**
 * Category names in [challengerScores] (excluding "overall") whose mean score is more than [cap]
 * below the matching category in [baselineScores]. A category present only in [challengerScores]
 * is ignored — there's nothing to compare it against. Shared by [evaluateAcceptance] and
 * `SkillVerification.kt`'s `recommendFromScores`.
 */
fun regressedCategories(
    baselineScores: Map<String, List<Double>>,
    challengerScores: Map<String, List<Double>>,
    cap: Double
): List<String> = challengerScores.entries.filter { (category, scores) ->
    category != "overall" && baselineScores[category]?.let { baseline -> mean(baseline) - mean(scores) > cap } == true
}.map { it.key }

/**
 * Pure function, no LLM/IO — deterministic math over repeated-run score samples. [incumbentScores]
 * and [challengerScores] map category name to that category's per-run scores; both must include an
 * `"overall"` entry (the headline score across repeated runs). A category present only in one map
 * is ignored for the regression check — there's nothing to compare it against.
 */
fun evaluateAcceptance(
    incumbentScores: Map<String, List<Double>>,
    challengerScores: Map<String, List<Double>>,
    perCategoryRegressionCap: Double = 0.02
): TournamentResult {
    val incumbentOverall = incumbentScores.getValue("overall")
    val challengerOverall = challengerScores.getValue("overall")
    val noiseFloor = stddev(incumbentOverall)
    val improvement = mean(challengerOverall) - mean(incumbentOverall)
    val requiresManualReview = improvement > REWARD_HACKING_JUMP_THRESHOLD

    if (improvement <= noiseFloor) {
        return TournamentResult(
            accepted = false, reason = "improvement did not exceed the noise floor",
            requiresManualReview = requiresManualReview
        )
    }

    val regressed = regressedCategories(incumbentScores, challengerScores, perCategoryRegressionCap)
    if (regressed.isNotEmpty()) {
        return TournamentResult(
            accepted = false, reason = "category '${regressed.first()}' regressed beyond the cap",
            requiresManualReview = requiresManualReview
        )
    }

    return TournamentResult(
        accepted = true, reason = "improvement exceeded the noise floor with no category regression",
        requiresManualReview = requiresManualReview
    )
}

data class TournamentRunResult(val challengerVersionId: String, val result: TournamentResult)

/** Shared by `sophi tournament run`'s stdout output and [TournamentTool]'s tool output, so the
 *  two can't drift apart. */
fun format(outcome: TournamentRunResult): String = buildString {
    appendLine("challenger version: ${outcome.challengerVersionId}")
    appendLine("accepted=${outcome.result.accepted}  reason=${outcome.result.reason}")
    if (outcome.result.requiresManualReview) {
        appendLine(
            "MANUAL REVIEW REQUIRED: the score jump is large enough to be suspect regardless " +
                "of the statistical result above."
        )
    }
    if (outcome.result.accepted) {
        appendLine("Run `sophi tournament promote ${outcome.challengerVersionId}` to make it the active config.")
    }
}.trimEnd()

/**
 * Proposes one challenger, records it (regardless of outcome — history is append-only), runs both
 * the incumbent and the challenger through [cases] [runsPerConfig] times each, and evaluates the
 * result. [runsPerConfig] doubles as the noise-floor sample size [evaluateAcceptance] needs.
 */
suspend fun runTournament(
    incumbentVersionId: String,
    versionStore: VersionStore,
    scorecardStore: ScorecardStore,
    cases: List<EvalCase>,
    provider: LLMProvider,
    registry: ToolRegistry,
    sessionManager: SessionManager,
    contextWindowTokens: Int,
    model: String,
    unaddressedFailureModes: List<String>,
    toolStats: Map<String, ToolStats>,
    runsPerConfig: Int = 3,
    env: (String) -> String? = System::getenv
): TournamentRunResult {
    check(env(TOURNAMENT_ENABLED_ENV)?.lowercase() == "true") {
        "Tournament mutation is disabled by default (still on probation) -- set $TOURNAMENT_ENABLED_ENV=true to enable it."
    }
    val incumbentVersion = versionStore.get(incumbentVersionId) ?: error("no such config version: $incumbentVersionId")
    val incumbent = tournamentJson.decodeFromString<HarnessConfig>(incumbentVersion.content)
    val challenger = proposeMutation(provider, model, incumbent, unaddressedFailureModes, toolStats)
    val challengerVersion = versionStore.record(
        ArtifactType.CONFIG, incumbentVersion.artifactId,
        tournamentJson.encodeToString(HarnessConfig.serializer(), challenger), ProducedBy.TOURNAMENT
    )

    val incumbentScores = mutableMapOf<String, MutableList<Double>>()
    val challengerScores = mutableMapOf<String, MutableList<Double>>()

    repeat(runsPerConfig) {
        val incumbentScorecard = runSuite(
            cases, provider, registry, sessionManager, contextWindowTokens, model,
            incumbentVersion.id, incumbent.systemPrompt, scorecardStore
        )
        incumbentScores.getOrPut("overall") { mutableListOf() }.add(incumbentScorecard.headlineScore)
        incumbentScorecard.perCategory.forEach { (cat, score) -> incumbentScores.getOrPut(cat) { mutableListOf() }.add(score) }

        val challengerScorecard = runSuite(
            cases, provider, registry, sessionManager, contextWindowTokens, model,
            challengerVersion.id, challenger.systemPrompt, scorecardStore
        )
        challengerScores.getOrPut("overall") { mutableListOf() }.add(challengerScorecard.headlineScore)
        challengerScorecard.perCategory.forEach { (cat, score) -> challengerScores.getOrPut(cat) { mutableListOf() }.add(score) }
    }

    val result = evaluateAcceptance(incumbentScores, challengerScores)
    return TournamentRunResult(challengerVersion.id, result)
}

/** Shared by `sophi tournament promote` (human-confirmed) and `sophi config activate` (instant,
 *  no confirmation) — both just record a new [ArtifactType.CONFIG] version copying [versionId]'s
 *  content forward, distinguished by [note] for [tournamentStatus]'s counters. */
fun activateConfigVersion(store: VersionStore, versionId: String, note: String): Version {
    val target = store.get(versionId) ?: error("no such version: $versionId")
    return store.record(ArtifactType.CONFIG, target.artifactId, target.content, ProducedBy.HUMAN, note)
}

data class TournamentStatus(val challengersProposed: Int, val promotionsAccepted: Int)

/** [challengersProposed] counts only original tournament proposals (ProducedBy.TOURNAMENT, no
 *  note) — [activateConfigVersion] always records ProducedBy.HUMAN, so promoting a challenger
 *  never double-counts as a second proposal. */
fun tournamentStatus(store: VersionStore): TournamentStatus {
    val configVersions = store.allForType(ArtifactType.CONFIG)
    return TournamentStatus(
        challengersProposed = configVersions.count { it.producedBy == ProducedBy.TOURNAMENT && it.note == null },
        promotionsAccepted = configVersions.count { it.note == "promoted" }
    )
}
