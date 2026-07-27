package dev.sophi.mcp

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool

class McpTool(
    private val session: McpSession,
    serverName: String,
    remoteTool: RemoteToolInfo,
    private val safeTools: Set<String>
) : Tool {
    private val remoteName = remoteTool.name

    override val name = "${serverName}__$remoteName"
    override val description = remoteTool.description
    override val parametersJson = remoteTool.inputSchemaJson
    override fun riskLevel(argumentsJson: String): RiskLevel =
        if (remoteName in safeTools) RiskLevel.SAFE else RiskLevel.DESTRUCTIVE

    override suspend fun execute(argumentsJson: String): String =
        try {
            session.callTool(remoteName, argumentsJson)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
}
