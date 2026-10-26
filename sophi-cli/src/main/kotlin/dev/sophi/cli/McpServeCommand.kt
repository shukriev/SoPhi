package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.core.ProgramResult
import dev.sophi.core.permissions.PermissionRulesException
import dev.sophi.sdk.builtinMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

class McpServeCommand : CliktCommand(
    name = "mcp-serve",
    help = "Expose an allowlist of Sophi's tools as an MCP server over stdio"
) {
    private val exposeTools: List<String> by option(
        "--expose-tools",
        help = "Comma-separated list of tool names to expose (e.g. grep,read_file,glob)"
    ).split(",").required()
    private val braveApiKeyOption: String? by option(
        "--brave-api-key",
        help = "Brave Search API key for the web_search tool (falls back to BRAVE_SEARCH_API_KEY; omit to disable web_search)"
    )

    override fun run() = runBlocking {
        // stdout carries the protocol, so a malformed rules file is reported on stderr and the server never starts
        val server = try {
            builtinMcpServer(exposeTools.toSet(), braveApiKey = braveApiKeyOption)
        } catch (e: PermissionRulesException) {
            System.err.println("Error: ${e.message}")
            throw ProgramResult(1)
        }
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered()
        )
        val closed = CompletableDeferred<Unit>()
        transport.onClose { closed.complete(Unit) }
        server.createSession(transport)
        closed.await()
    }
}
