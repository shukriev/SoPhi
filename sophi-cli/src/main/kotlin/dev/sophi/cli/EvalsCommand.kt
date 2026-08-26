package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
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
    private val model: String by option("--model", "-m").default("claude-3-5-sonnet-20241022")
    private val providerType: String by option("--provider").default("claude")
    private val baseUrl: String? by option("--base-url")
    private val apiKeyOption: String? by option("--api-key")
    private val sessionsDirStr: String by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")
    private val versioningHomeStr: String by option("--versioning-home")
        .default("${System.getProperty("user.home")}/.sophi/versioning")

    override fun run() {
        val allCases = loadEvalCases(Path.of(evalsDirStr))
        val cases = if (category == null) allCases else allCases.filter { it.category == category }
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
        EvalsRun(
            cases = cases, provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(Path.of(sessionsDirStr)),
            contextWindowTokens = EVAL_CONTEXT_WINDOW, model = model, configVersionId = configVersion,
            systemPrompt = null, scorecardStore = ScorecardStore(Path.of(versioningHomeStr))
        ) { echo(it) }.run()
    }
}

class EvalsCommand : CliktCommand(name = "evals", help = "Run the eval suite against a versioned config") {
    override fun run() = Unit
}
