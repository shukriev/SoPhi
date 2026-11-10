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

    /**
     * Called when a server announces a changed tool list: the tools to register (always deferred, so
     * ToolSearch can find them) and the names to unregister. Runs on a background thread.
     */
    @Volatile
    var onToolsChanged: ((added: List<Tool>, removed: List<String>) -> Unit)? = null

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
            var known = remoteTools.map { it.name }.toSet()
            (session as? ToolsChangeSource)?.onToolsChanged {
                val now = try { session.listTools() } catch (e: Exception) { return@onToolsChanged }
                val added = now.filter { it.name !in known }
                    .map { McpTool(session, config.name, it, safeTools, deferred = true, mask = mask::mask) }
                val removed = (known - now.map { it.name }.toSet()).map { "${config.name}__$it" }
                known = now.map { it.name }.toSet()
                if (added.isNotEmpty() || removed.isNotEmpty()) onToolsChanged?.invoke(added, removed)
            }
            remoteTools.map { McpTool(session, config.name, it, safeTools, deferred = defer, mask = mask::mask) }
        } catch (e: Exception) {
            val signIn = e.signInRequired()
            val message = e.message?.let(mask::mask)
            // A sign-in refusal was already told through McpOAuth's onSignInNeeded.
            if (signIn == null) System.err.println("Warning: MCP server '${config.name}' failed to connect or list tools: $message")
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
