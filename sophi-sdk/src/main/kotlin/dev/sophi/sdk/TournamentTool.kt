package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.loadEvalCases
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import dev.sophi.versioning.VersioningStores
import java.nio.file.Path

/**
 * Lets a scheduled Goal-mode task run `sophi tournament run` and reflect on the result in its own
 * words — the reflection is what the memory encoder (AFTER_TURN, see ScheduleEngine) distills into
 * durable episodes. RiskLevel.SAFE: runTournament proposes+scores+records history but never mutates
 * the *active* config (promotion is a separate, human-confirmed step) — safe for unattended use now
 * that VersionStore.activeVersion (not "newest record") is what selects the incumbent.
 *
 * Uses its own empty ToolRegistry for the eval sub-agent runTournament drives internally — never
 * the caller's full registry — so the eval agent can't recursively call this tool.
 */
class TournamentTool(
    private val provider: LLMProvider,
    private val model: String,
    private val contextWindowTokens: Int,
    sessionsDir: Path,
    private val versioningHome: Path = VersioningStores.DEFAULT_VERSIONING_HOME,
    private val evalsDir: Path = Path.of("evals"),
    private val runsPerConfig: Int = 3,
    private val sharedRoot: Path = ArcadeHost.defaultRoot(),
    private val shared: Map<Path, String> = VersioningStores.DEFAULT_SHARED
) : Tool {
    private val sessionManager = FileSessionManager(sessionsDir)

    override val name = "run_tournament"
    override val description =
        "Propose a config mutation and score it against the eval suite (2 configs x eval cases x " +
            "repeated runs, so this can take several minutes). Reports whether the challenger was " +
            "accepted, the score delta versus the incumbent, and whether it looks worth a manual " +
            "`sophi tournament promote`. Requires SOPHI_TOURNAMENT_ENABLED=true in the environment " +
            "and a seeded default config version (`sophi config seed`)."
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val db = runCatching { VersioningStores.db(versioningHome, sharedRoot, shared) }
            .getOrElse { return "versioning unavailable: ${it.message}" }
        try {
            val versionStore = VersionStore(db)
            val scorecardStore = ScorecardStore(db)
            val incumbentVersionId = versionStore.activeVersion(ArtifactType.CONFIG, "default")?.id
                ?: return "No config version found for 'default' — run `sophi config seed` first."
            val cases = loadEvalCases(evalsDir)
            if (cases.isEmpty()) return "No eval cases found in $evalsDir — nothing to score a tournament against."

            val outcome = runCatching {
                runTournament(
                    incumbentVersionId = incumbentVersionId,
                    versionStore = versionStore,
                    scorecardStore = scorecardStore,
                    cases = cases,
                    provider = provider,
                    registry = ToolRegistry(),
                    sessionManager = sessionManager,
                    contextWindowTokens = contextWindowTokens,
                    model = model,
                    unaddressedFailureModes = emptyList(),
                    toolStats = emptyMap(),
                    runsPerConfig = runsPerConfig
                )
            }.getOrElse { e -> return e.message ?: "tournament run failed" }

            return format(outcome)
        } finally { db.close() }
    }
}
