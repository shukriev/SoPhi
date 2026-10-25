package dev.sophi.mcp.server

import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.auditEntry
import dev.sophi.core.audit.recordQuietly
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
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
 * With [audit], every call writes one entry (actor = the client's handshake name, else "mcp-client"; rule "safe"
 * or "deny-all"), redacted and capped exactly like AgentLoop's. A failing sink never changes the response.
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
fun buildMcpServer(tools: List<Tool>, exposedNames: Set<String>, audit: AuditSink? = null): Server {
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
            // Actor: the name the client gave in its initialize handshake.
            fun audit(outcome: String, result: String) = audit?.recordQuietly {
                val actor = server.sessions[sessionId]?.clientVersion?.name ?: "mcp-client"
                auditEntry(actor, null, "mcp-serve:$sessionId", tool.name, risk, argumentsJson,
                    if (outcome == "denied") "deny-all" else "safe", outcome, result, start)
            }
            if (risk != RiskLevel.SAFE) {
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
                    audit(if (result.startsWith("Error: ")) "error" else "ok", result)
                    CallToolResult(content = listOf(TextContent(result)))
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
