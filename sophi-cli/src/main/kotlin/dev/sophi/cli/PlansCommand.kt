package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.dryrun.listLine
import dev.sophi.core.dryrun.replayReport
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.SophiRuntime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.exists

private const val PLANS_USAGE = "Usage: plans [list] | plans show|approve|run|cancel <id>"

/** No one presses ESC outside the REPL: a replay runs to its end. */
internal object NoEscInput : InputSource by NoOpInputSource {
    override suspend fun awaitEsc() = awaitCancellation()
}

/** `list` (default), `show`, `approve` (approve and run a pending plan), `run` (an approved one), `cancel`. */
internal suspend fun plansAction(action: String?, id: String?, runtime: SophiRuntime, input: InputSource, output: (String) -> Unit) {
    if (action == null || action == "list") {
        val plans = runtime.plans()
        return if (plans.isEmpty()) output("No plans.") else plans.forEach { output(it.listLine()) }
    }
    if (id == null || action !in setOf("show", "approve", "run", "cancel")) return output(PLANS_USAGE)
    val plan = runtime.plan(id) ?: return output("No plan $id")
    try {
        when (action) {
            "show" -> {
                output(renderPlan(plan, runtime::maskSecrets, full = true, dropped = plan.dropped))
                output("Status: ${plan.status.name.lowercase()}")
                plan.results.takeIf { it.isNotEmpty() }?.let { output(runtime.maskSecrets(plan.replayReport())) }
            }
            "approve" -> {
                output(renderPlan(plan, runtime::maskSecrets, dropped = plan.dropped))
                runtime.approvePlan(id, plan.dropped)
                runApprovedPlan(runtime, id, input, output)
            }
            "run" -> runApprovedPlan(runtime, id, input, output)
            "cancel" -> { runtime.cancelPlan(id); output("Cancelled; nothing ran.") }
        }
    } catch (e: IllegalStateException) {
        output(e.message ?: "That didn't work")
    } catch (e: IllegalArgumentException) {
        output(e.message ?: "That didn't work")
    }
}

/** `sophi plans` never asks a model anything; the runtime still needs a provider. */
private object NoModel : LLMProvider {
    override val name = "none"
    override suspend fun complete(request: CompletionRequest): LLMResponse = error("sophi plans doesn't call a model")
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = error("sophi plans doesn't call a model")
}

class PlansCommand : CliktCommand(name = "plans", help = "Changes SoPhi recorded for your approval: list, show, approve, run, cancel") {
    private val action by argument(help = "list (default), show, approve, run or cancel").optional()
    private val id by argument(help = "The plan id").optional()
    private val mcpConfig by option("--mcp-config", help = "MCP servers to run plans with (default: .sophi/mcp.json here, else ~/.sophi/mcp.json)")

    override fun run() = runBlocking {
        val mcp = mcpConfig?.let { Path.of(it) }
            ?: Path.of(".sophi", "mcp.json").takeIf { it.exists() } ?: defaultScheduleMcpConfig()
        val runtime = Sophi.runtime {
            provider = NoModel
            model = "none"
            contextWindowTokens(1)
            builtinTools(root = Path.of("").toAbsolutePath())
            checkpoints()
            auditLog()
            credentials()
            if (mcp.exists()) mcpConfig(mcp)
            planFirst()
        }
        try { plansAction(action, id, runtime, NoEscInput) { echo(it) } } finally { runtime.close() }
    }
}
