package dev.sophi.mcp

import dev.sophi.core.tools.TOOL_ERROR_PREFIX
import dev.sophi.core.tools.isToolError
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Wraps the real MCP SDK's [Client] to implement [McpSession].
 *
 * Note: [CallToolRequest] in the real SDK (0.14.0) wraps its `name`/`arguments` inside a
 * [CallToolRequestParams] object rather than taking them as direct constructor arguments.
 *
 * @param process the OS process backing this session's transport, if any (e.g. a stdio
 * subprocess spawned by [StdioMcpConnector]). Optional so that future non-process transports
 * (e.g. an HTTP-based connector) can construct a session without one. When present, [close]
 * force-destroys it as a backstop in case the server ignores stdin EOF and never exits on its own.
 * @param httpClient the Ktor [HttpClient] backing this session's transport, if any (e.g. one
 * created by [StreamableHttpMcpConnector]). Optional for the same reason as [process]. Required
 * because [Client.close] only closes the SDK's transport (cancels its SSE job/scope) and never
 * closes an externally-provided [HttpClient] — verified against the 0.14.0 SDK sources
 * (`StreamableHttpClientTransport.closeResources()`), which does not touch `client`. Without this,
 * every HTTP session close would leak the CIO engine's connection pool and worker threads.
 */
class SdkMcpSession(
    private val client: Client,
    private val process: Process? = null,
    private val httpClient: HttpClient? = null
) : McpSession {

    override suspend fun listTools(): List<RemoteToolInfo> =
        client.listTools().tools.map { tool ->
            RemoteToolInfo(
                name = tool.name,
                description = tool.description.orEmpty(),
                inputSchemaJson = Json.encodeToString(tool.inputSchema)
            )
        }

    override suspend fun callTool(name: String, argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
        val result = client.callTool(request)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        // The server's isError is the failure signal; carry it over in the loop's own convention.
        return if (result.isError == true && !isToolError(text)) "$TOOL_ERROR_PREFIX$text" else text
    }

    override suspend fun close() {
        try {
            client.close()
        } finally {
            try {
                process?.destroyForcibly()
            } finally {
                httpClient?.close()
            }
        }
    }
}
