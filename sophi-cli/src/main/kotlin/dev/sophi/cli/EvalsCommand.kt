package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.loadEvalCases
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.sdk.runSuite
import dev.sophi.versioning.ScorecardStore
import dev.sophi.versioning.VersioningStores
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private const val EVAL_CONTEXT_WINDOW = 100_000

class EvalsRun(
    private val cases: List<EvalCase>,
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val contextWindowTokens: Int,
    private val model: String,
    private val configVersionId: String,
    private val systemPrompt: String?,
    private val scorecardStore: ScorecardStore,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (cases.isEmpty()) {
            echo("No eval cases found.")
            return
        }
        val scorecard = runBlocking {
            runSuite(
                cases = cases, provider = provider, registry = registry, sessionManager = sessionManager,
                contextWindowTokens = contextWindowTokens, model = model, configVersionId = configVersionId,
                systemPrompt = systemPrompt, scorecardStore = scorecardStore
            )
        }
        echo("headline=%.2f  totalCases=%d  configVersion=%s".format(scorecard.headlineScore, scorecard.totalCases, configVersionId))
        scorecard.perCategory.forEach { (category, score) -> echo("  $category: %.2f".format(score)) }
        if (scorecard.quarantinedCaseIds.isNotEmpty()) {
            echo("quarantined (excluded from headline): ${scorecard.quarantinedCaseIds.joinToString(", ")}")
        }
    }
}

class EvalsRunCommand : CliktCommand(name = "run", help = "Run the eval suite and report a scorecard") {
    private val evalsDirStr: String by option("--evals-dir").default("evals")
    private val category: String? by option("--category")
    private val configVersion: String by option("--config-version").default("default")
    private val provider by ProviderOptions()
    private val sessionsDirStr: String by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")
    private val versioningHomeStr: String by option("--versioning-home")
        .default(VersioningStores.DEFAULT_VERSIONING_HOME.toString())

    override fun run() {
        val allCases = loadEvalCases(Path.of(evalsDirStr))
        val cases = if (category == null) allCases else allCases.filter { it.category == category }
        val resolved = provider.resolveAndWarn()
        val llm = provider.buildProvider(resolved)
        openStore { VersioningStores.scorecardStore(Path.of(versioningHomeStr)) }.use { scorecardStore ->
        EvalsRun(
            cases = cases, provider = llm, registry = ToolRegistry(),
            sessionManager = FileSessionManager(Path.of(sessionsDirStr)),
            contextWindowTokens = resolved.windowAtMost(EVAL_CONTEXT_WINDOW), model = resolved.model, configVersionId = configVersion,
            systemPrompt = null, scorecardStore = scorecardStore
        ) { echo(it) }.run()
        }
    }
}

class EvalsCommand : CliktCommand(name = "evals", help = "Run the eval suite against a versioned config") {
    override fun run() = Unit
}
