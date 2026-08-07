package dev.sophi.mcp

import dev.sophi.core.tools.RiskLevel
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
    private val timeout: Duration = DEFAULT_MCP_TOOL_TIMEOUT
) : Tool {
    private val remoteName = remoteTool.name

    override val name = "${serverName}__$remoteName"
    override val description = remoteTool.description
    override val parametersJson = remoteTool.inputSchemaJson
    override fun riskLevel(argumentsJson: String): RiskLevel =
        if (remoteName in safeTools) RiskLevel.SAFE else RiskLevel.DESTRUCTIVE

    override suspend fun execute(argumentsJson: String): String =
        try {
            withTimeout(timeout) {
                session.callTool(remoteName, argumentsJson)
            }
        } catch (e: TimeoutCancellationException) {
            "Error: MCP tool '$remoteName' timed out after $timeout"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
}
