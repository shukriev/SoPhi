package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

/**
 * Connects to a remote MCP server over the Streamable HTTP transport.
 *
 * Note: the real SDK's [StreamableHttpClientTransport] constructor (0.14.0) matches the brief
 * exactly (`client`/`url` parameters), unlike [StdioMcpConnector]'s transport. There's no OS
 * subprocess for HTTP, so [SdkMcpSession] is constructed with its `process` parameter left at its
 * default (`null`).
 */
class StreamableHttpMcpConnector : McpConnector {
    override suspend fun connect(config: McpServerConfig): McpSession {
        val url = requireNotNull(config.url) { "http MCP server '${config.name}' requires a url" }
        val httpClient = HttpClient { install(SSE) }
        val transport = StreamableHttpClientTransport(client = httpClient, url = url)
        val client = Client(clientInfo = Implementation(name = "sophi", version = "1.0.0"))
        client.connect(transport)
        return SdkMcpSession(client)
    }
}
