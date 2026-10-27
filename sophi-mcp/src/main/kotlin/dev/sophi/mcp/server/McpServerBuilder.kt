package dev.sophi.mcp.server

import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.auditEntry
import dev.sophi.core.audit.recordQuietly
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.isToolError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Builds an MCP SDK [Server] that exposes exactly the tools named in [exposedNames] (a subset of
 * [tools]) over the MCP protocol.
 *
 * The `addTool(name, description) { request -> ... }` handler receives a
 * `CallToolRequest` (confirmed via the 0.14.0 sources under
 * `kotlin-sdk-core-jvm-0.14.0-sources.jar!/.../types/tools.kt`), which exposes `.arguments` as a
 * `JsonObject?` directly (delegating to `CallToolRequestParams.arguments`) — the same shape
 * assumed by the task brief, so no deviation was needed here.
 *
 * With [audit], every call writes one entry (actor [mcpActor]; rule "safe"
 * or "deny-all"), redacted and capped exactly like AgentLoop's. A failing sink never changes the response.
 *
 * A [permissions] deny or ask rule refuses a call, SAFE or not (audited as rule:deny / rule:ask): there is
 * no human here to ask. Allow rules never widen mcp-serve past SAFE tools.
 *
 * DESTRUCTIVE tools are never executed when called via this server: [Tool.execute] is not invoked
 * for them at all, and the caller instead receives an error [CallToolResult] denying the call.
 *
 * Each exposed tool's [Tool.parametersJson] (a JSON Schema string) is parsed into the SDK's
 * [ToolSchema] and passed as `addTool`'s `inputSchema` parameter (confirmed via the 0.14.0 sources
 * under `kotlin-sdk-server-jvm-0.14.0-sources.jar!/.../server/Server.kt`'s
 * `addTool(name, description, inputSchema = ToolSchema(), ...)` overload, and
 * `kotlin-sdk-core-jvm-0.14.0-sources.jar!/.../types/tools.kt`'s `ToolSchema` data class), so that
 * external MCP clients receive real argument descriptions instead of the overload's empty default.
 * [McpJson] (the SDK's own pre-configured `ignoreUnknownKeys = true` instance) is used for parsing
 * since [ToolSchema] declares its `type` property outside the primary constructor.
 */
fun buildMcpServer(tools: List<Tool>, exposedNames: Set<String>, audit: AuditSink? = null, permissions: PermissionRules? = null): Server {
    require(exposedNames.isNotEmpty()) { "--expose-tools must name at least one tool" }
    val toolsByName = tools.associateBy { it.name }
    val unknown = exposedNames - toolsByName.keys
    require(unknown.isEmpty()) { "Unknown tool(s) in --expose-tools: ${unknown.joinToString(", ")}" }

    val server = Server(
        serverInfo = Implementation(name = "sophi", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
    )

    exposedNames.forEach { name ->
        val tool = toolsByName.getValue(name)
        val inputSchema = McpJson.decodeFromString<ToolSchema>(tool.parametersJson)
        server.addTool(name = tool.name, description = tool.description, inputSchema = inputSchema) { request ->
            val start = System.currentTimeMillis()
            val argumentsJson = Json.encodeToString(request.arguments ?: JsonObject(emptyMap()))
            val risk = tool.riskLevel(argumentsJson)
            // A tool that throws computing its subjects gets null: R3, fail closed.
            val effect = permissions?.decide(tool.name, runCatching { tool.permissionSubjects(argumentsJson) }.getOrNull())
            val ruleDenied = effect == Effect.DENY || effect == Effect.ASK
            fun audit(outcome: String, result: String) = audit?.recordQuietly {
                val rule = when {
                    effect == Effect.DENY -> "rule:deny"
                    effect == Effect.ASK -> "rule:ask"
                    outcome == "denied" -> "deny-all"
                    else -> "safe"
                }
                auditEntry(mcpActor(server.sessions[sessionId]?.clientVersion?.name), null, "mcp-serve:$sessionId", tool.name, risk, argumentsJson,
                    rule, outcome, result, start)
            }
            if (ruleDenied) {
                audit("denied", "")
                CallToolResult(isError = true, content = listOf(TextContent("Denied: '${tool.name}' is denied by a permission rule")))
            } else if (risk != RiskLevel.SAFE) {
                audit("denied", "")
                CallToolResult(
                    isError = true,
                    content = listOf(
                        TextContent("Denied: '${tool.name}' is not a SAFE tool and cannot be called via mcp-serve")
                    )
                )
            } else {
                try {
                    val result = tool.execute(argumentsJson)
                    val failed = isToolError(result)
                    audit(if (failed) "error" else "ok", result)
                    // A client reads isError, not our text convention.
                    CallToolResult(isError = failed, content = listOf(TextContent(result)))
                } catch (e: Exception) {
                    if (e is CancellationException) { audit("cancelled", ""); throw e }
                    audit("error", "Error: ${e.message}")
                    CallToolResult(isError = true, content = listOf(TextContent("Error: ${e.message}")))
                }
            }
        }
    }
    return server
}

/** The audit actor for an MCP client: its handshake name, prefixed so a client can't pose as "local". */
internal fun mcpActor(clientName: String?): String = clientName?.takeIf { it.isNotBlank() }?.let { "mcp:$it" } ?: "mcp-client"
