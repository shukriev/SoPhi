package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.util.concurrent.TimeUnit

/**
 * A packaged/Dock-launched macOS app inherits a minimal system PATH (no Homebrew, no nvm), so a
 * bare command like "npx" fails to spawn even though it works fine from a terminal. Resolves the
 * user's real PATH by asking their login shell for it, the same way editors like VS Code work
 * around this. Bounded by [timeoutSeconds] so a broken or hanging shell can never stall startup;
 * any failure (missing shell, timeout, non-zero exit) returns null rather than throwing.
 */
internal fun resolveLoginShellPath(shell: String, timeoutSeconds: Long = 5): String? = runCatching {
    val process = ProcessBuilder(shell, "-l", "-c", "echo -n \$PATH").redirectErrorStream(true).start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    if (process.exitValue() != 0) return@runCatching null
    process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * Spawns the configured command as a subprocess and connects to it over the MCP SDK's stdio
 * transport.
 *
 * Note: the real SDK's [StdioClientTransport] constructor takes `input`/`output` (not
 * `inputStream`/`outputStream`) as its parameter names.
 */
class StdioMcpConnector(
    private val loginShell: String = System.getenv("SHELL") ?: "/bin/zsh"
) : McpConnector {
    // Computed once and reused for every connect() call: McpClientManager holds one
    // StdioMcpConnector for the whole SophiRuntime lifetime, and the login shell's PATH doesn't
    // change mid-run.
    private val resolvedPath: String? by lazy { resolveLoginShellPath(loginShell) }

    override suspend fun connect(config: McpServerConfig): McpSession {
        require(config.command.isNotEmpty()) { "stdio MCP server '${config.name}' requires a non-empty command" }
        val processBuilder = ProcessBuilder(config.command).redirectErrorStream(false)
        resolvedPath?.let { processBuilder.environment()["PATH"] = it }
        processBuilder.environment().putAll(config.env)
        val process = processBuilder.start()

        val client = connectOrDestroy(process) {
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )
            val client = Client(clientInfo = Implementation(name = "sophi", version = "1.0.0"))
            client.connect(transport)
            client
        }
        return SdkMcpSession(client, process)
    }

    /**
     * Runs [connectBlock] (spawns the transport and performs the MCP handshake) against an
     * already-started [process]. If [connectBlock] throws for any reason (server crashes, hangs
     * mid-handshake, or never speaks the protocol), the already-started subprocess must not be
     * leaked: it is force-killed before the failure is propagated.
     *
     * Pulled out as its own function (rather than inlined into [connect]) so the "destroy the
     * process on failure" behavior can be exercised directly in tests with a synthetic failure,
     * independent of the real SDK's handshake timing.
     */
    internal suspend fun <T> connectOrDestroy(process: Process, connectBlock: suspend () -> T): T =
        try {
            connectBlock()
        } catch (error: Throwable) {
            process.destroyForcibly()
            throw error
        }
}
