package dev.sophi.mcp

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.tools.Tool
import dev.sophi.mcp.config.DEFAULT_DEFER_TOOLS_OVER
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import dev.sophi.mcp.auth.signInRequired
import dev.sophi.mcp.config.MissingSecretException
import dev.sophi.mcp.config.resolveSecrets
import kotlinx.coroutines.runBlocking

class McpClientManager(
    private val stdioConnector: McpConnector = StdioMcpConnector(),
    private val httpConnector: McpConnector = StreamableHttpMcpConnector(),
    /** Where `${secret:name}` references in a server's config come from; null = any reference fails that server. */
    private val credentials: CredentialStore? = null,
    val mask: SecretMask = SecretMask(),
) : AutoCloseable {

    private val sessions = mutableMapOf<String, McpSession>()

    suspend fun connect(configs: List<McpServerConfig>, deferToolsOver: Int = DEFAULT_DEFER_TOOLS_OVER): List<Tool> =
        configs.flatMap { connectOne(it, deferToolsOver) }

    /**
     * [onFailure] is a diagnostic side channel only — connect-all callers (sophi-cli, sophi-web)
     * rely on a failed server being silently skipped so the rest still connect, so this keeps
     * returning `emptyList()` on failure rather than throwing. A caller that needs to show the
     * user *why* a server failed (sophi-companion's Notifications tab) passes [onFailure] to
     * capture the real exception instead of just the generic "connected but registered no tools".
     */
    suspend fun connectOne(config: McpServerConfig,
        deferToolsOver: Int = DEFAULT_DEFER_TOOLS_OVER,
        onFailure: (Throwable) -> Unit = {}): List<Tool> {
        val connector = when (config.transport) {
            McpTransport.STDIO -> stdioConnector
            McpTransport.HTTP -> httpConnector
        }
        return try {
            val session = connector.connect(resolveSecrets(config, credentials, mask))
            sessions[config.name] = session
            val safeTools = config.safeTools.toSet()
            val remoteTools = session.listTools()
            val defer = remoteTools.size > deferToolsOver
            remoteTools.map { McpTool(session, config.name, it, safeTools, deferred = defer, mask = mask::mask) }
        } catch (e: Exception) {
            val signIn = e.signInRequired()
            val message = e.message?.let(mask::mask)
            if (signIn != null) System.err.println("${config.name} needs sign-in — run `sophi mcp login ${config.name}`")
            else System.err.println("Warning: MCP server '${config.name}' failed to connect or list tools: $message")
            // A secret can't ride out in the exception: callers show its message, and its cause chain may hold the value.
            onFailure(signIn ?: if (e is MissingSecretException) e else Exception(message))
            emptyList()
        }
    }

    suspend fun disconnect(serverName: String) {
        sessions.remove(serverName)?.let { session ->
            try {
                session.close()
            } catch (e: Exception) {
                System.err.println("Warning: failed to close MCP session '$serverName': ${e.message}")
            }
        }
    }

    override fun close() {
        runBlocking {
            sessions.values.forEach { session ->
                try {
                    session.close()
                } catch (e: Exception) {
                    System.err.println("Warning: failed to close an MCP session: ${e.message}")
                }
            }
            sessions.clear()
        }
    }
}
