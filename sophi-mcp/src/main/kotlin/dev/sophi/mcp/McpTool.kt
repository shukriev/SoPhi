package dev.sophi.mcp

import dev.sophi.core.tools.EffectKind
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.mcp.auth.signInRequired
import dev.sophi.core.tools.Tool
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_MCP_TOOL_TIMEOUT = 60.seconds

/**
 * [timeout] guards against a remote MCP server hanging mid-call (e.g. a Playwright-backed
 * server blocking forever inside a CDP connection attempt) — without it, a single stuck tool
 * call freezes the whole plan step with no error surfaced.
 */
class McpTool(
    private val session: McpSession,
    serverName: String,
    remoteTool: RemoteToolInfo,
    private val safeTools: Set<String>,
    private val timeout: Duration = DEFAULT_MCP_TOOL_TIMEOUT,
    override val deferred: Boolean = false,
    /** Masks live secrets out of the result, so a server echoing its token never shows it to the model. */
    private val mask: (String) -> String = { it },
) : Tool {
    private val remoteName = remoteTool.name
    private val readOnlyHint = remoteTool.readOnlyHint

    override val name = "${serverName}__$remoteName"
    override val description = remoteTool.description
    override val parametersJson = remoteTool.inputSchemaJson
    /** Whatever the call's [Reach]: a change's reply is the server's text too. */
    override val outsideText = true
    override fun riskLevel(argumentsJson: String): RiskLevel =
        if (remoteName in safeTools) RiskLevel.SAFE else RiskLevel.DESTRUCTIVE
    // ponytail: a server's hints never remove a kind (removals default off, decision 2); add a trust flag when one is wanted
    override fun effects(argumentsJson: String) =
        if (remoteName in safeTools) setOf(EffectKind.NETWORK) else setOf(EffectKind.WRITES, EffectKind.NETWORK)

    /** Derived from [effects], so the two can't drift; the hint only marks a would-be change as a vouched-for read. */
    override fun reach(argumentsJson: String): Reach {
        val fx = effects(argumentsJson)
        return when {
            EffectKind.NETWORK !in fx -> Reach.LOCAL
            EffectKind.WRITES !in fx -> Reach.OUTSIDE_READ
            readOnlyHint -> Reach.OUTSIDE_HINTED_READ
            else -> Reach.OUTSIDE_CHANGE
        }
    }

    override suspend fun execute(argumentsJson: String): String = mask(
        try {
            withTimeout(timeout) {
                session.callTool(remoteName, argumentsJson)
            }
        } catch (e: TimeoutCancellationException) {
            "Error: MCP tool '$remoteName' timed out after $timeout"
        } catch (e: Exception) {
            e.signInRequired()?.let { "Error: ${it.message}. Retrying won't help: tell the user to sign in." } ?: "Error: ${e.message}"
        }
    )
}
