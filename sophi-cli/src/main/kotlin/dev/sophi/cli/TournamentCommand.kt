package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.loadEvalCases
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.learning.ToolStats
import dev.sophi.sdk.activateConfigVersion
import dev.sophi.sdk.runTournament
import dev.sophi.sdk.tournamentStatus
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersionStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private const val TOURNAMENT_CONTEXT_WINDOW = 100_000

private val defaultVersioningHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "versioning")

class TournamentRun(
    private val incumbentVersionId: String,
    private val versionStore: VersionStore,
    private val scorecardStore: ScorecardStore,
    private val cases: List<EvalCase>,
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val contextWindowTokens: Int,
    private val model: String,
    private val runsPerConfig: Int,
    private val unaddressedFailureModes: List<String> = emptyList(),
    private val toolStats: Map<String, ToolStats> = emptyMap(),
    private val env: (String) -> String? = System::getenv,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (cases.isEmpty()) {
            echo("No eval cases found — nothing to score a tournament against.")
            return
        }
        val outcome = runCatching {
            runBlocking {
                runTournament(
                    incumbentVersionId = incumbentVersionId, versionStore = versionStore, scorecardStore = scorecardStore,
                    cases = cases, provider = provider, registry = registry, sessionManager = sessionManager,
                    contextWindowTokens = contextWindowTokens, model = model,
                    unaddressedFailureModes = unaddressedFailureModes, toolStats = toolStats,
                    runsPerConfig = runsPerConfig, env = env
                )
            }
        }.getOrElse { e ->
            echo(e.message ?: "tournament run failed")
            return
        }
        echo(dev.sophi.sdk.format(outcome))
    }
}

class TournamentPromote(
    private val versionStore: VersionStore,
    private val versionId: String,
    private val confirm: () -> Boolean,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (!confirm()) {
            echo("cancelled — no change made")
            return
        }
        if (versionStore.get(versionId) == null) {
            echo("No version found with id $versionId")
            return
        }
        activateConfigVersion(versionStore, versionId, note = "promoted")
        echo("promoted $versionId to the active config")
    }
}

class ConfigActivate(
    private val versionStore: VersionStore,
    private val versionId: String,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (versionStore.get(versionId) == null) {
            echo("No version found with id $versionId")
            return
        }
        activateConfigVersion(versionStore, versionId, note = "activated")
        echo("activated $versionId")
    }
}

class TournamentStatusReport(
    private val versionStore: VersionStore,
    private val echo: (String) -> Unit
) {
    fun run() {
        val status = tournamentStatus(versionStore)
        echo("challengersProposed=${status.challengersProposed}  promotionsAccepted=${status.promotionsAccepted}")
    }
}

class TournamentRunCommand : CliktCommand(name = "run", help = "Propose a config mutation and score it against the eval suite") {
    private val configVersionId: String by option("--config-version").default("default")
    private val evalsDirStr: String by option("--evals-dir").default("evals")
    private val budget: Int by option("--budget", help = "Repeated runs per config -- also the noise-floor sample size").int().default(3)
    private val model: String by option("--model", "-m").default("claude-3-5-sonnet-20241022")
    private val providerType: String by option("--provider").default("claude")
    private val baseUrl: String? by option("--base-url")
    private val apiKeyOption: String? by option("--api-key")
    private val sessionsDirStr: String by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")
    private val versioningHomeStr: String by option("--versioning-home").default(defaultVersioningHome.toString())

    override fun run() {
        val versionStore = VersionStore(Path.of(versioningHomeStr))
        val incumbentVersionId = versionStore.activeVersion(ArtifactType.CONFIG, "default")?.id
            ?: run { echo("No config version found for 'default' — nothing to run a tournament against."); return }
        val cases = loadEvalCases(Path.of(evalsDirStr))
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
        TournamentRun(
            incumbentVersionId = incumbentVersionId, versionStore = versionStore,
            scorecardStore = ScorecardStore(Path.of(versioningHomeStr)), cases = cases, provider = provider,
            registry = ToolRegistry(), sessionManager = FileSessionManager(Path.of(sessionsDirStr)),
            contextWindowTokens = TOURNAMENT_CONTEXT_WINDOW, model = model, runsPerConfig = budget
        ) { echo(it) }.run()
    }
}

class TournamentPromoteCommand : CliktCommand(name = "promote", help = "Promote a challenger config version to active, with confirmation") {
    private val versionId: String by argument()
    private val versioningHomeStr: String by option("--versioning-home").default(defaultVersioningHome.toString())

    override fun run() {
        val versionStore = VersionStore(Path.of(versioningHomeStr))
        TournamentPromote(versionStore, versionId, confirm = {
            echo("Promote $versionId to the active config? [y/N] ", trailingNewline = false)
            readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true
        }) { echo(it) }.run()
    }
}

class TournamentStatusCommand : CliktCommand(name = "status", help = "Show tournament probation counters") {
    private val versioningHomeStr: String by option("--versioning-home").default(defaultVersioningHome.toString())

    override fun run() = TournamentStatusReport(VersionStore(Path.of(versioningHomeStr))) { echo(it) }.run()
}

class TournamentCommand : CliktCommand(name = "tournament", help = "Propose, test, and promote config mutations") {
    override fun run() = Unit
}

class ConfigActivateCommand : CliktCommand(name = "activate", help = "Instantly switch the active config to a given version") {
    private val versionId: String by argument()
    private val versioningHomeStr: String by option("--versioning-home").default(defaultVersioningHome.toString())

    override fun run() = ConfigActivate(VersionStore(Path.of(versioningHomeStr)), versionId) { echo(it) }.run()
}

class ConfigCommand : CliktCommand(name = "config", help = "Inspect and switch the active harness config") {
    override fun run() = Unit
}
