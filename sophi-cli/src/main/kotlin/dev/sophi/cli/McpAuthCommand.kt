package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.core.credentials.KeychainCredentialStore
import dev.sophi.core.credentials.OAuthFlow
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.tools.SystemDesktop
import dev.sophi.mcp.auth.McpOAuth
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import dev.sophi.mcp.config.resolveSecrets
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Path

/** `login`/`logout` for the server named [server] in [mcpConfig]; the reply to show. Shared by `sophi mcp` and `/mcp`. */
suspend fun mcpAuth(
    action: String, server: String, mcpConfig: Path,
    login: suspend (McpServerConfig) -> Result<List<String>>, logout: suspend (String) -> Unit,
): String {
    val config = McpConfigLoader().load(mcpConfig).servers.firstOrNull { it.name == server }
        ?: return "No MCP server named '$server' in mcp.json."
    if (config.transport != McpTransport.HTTP) return "$server runs locally; give it keys with \${secret:name} in its env instead."
    return when (action) {
        "login" -> login(config).fold(
            { tools -> if (tools.isEmpty()) "Signed in to $server." else "Signed in to $server — ${tools.size} tools connected." },
            { e -> "Couldn't sign in to $server: ${e.message}" },
        )
        "logout" -> { logout(server); "Signed out of $server." }
        else -> "Usage: /mcp login <server> | /mcp logout <server>"
    }
}

/** Opens the sign-in page in the default browser. */
internal val openInBrowser: (URI) -> Unit = { uri -> runBlocking { SystemDesktop.detect().open(uri.toString()) } }

class McpCommand : CliktCommand(name = "mcp", help = "Sign in to remote MCP servers") {
    override fun run() = Unit
}

class McpLoginCommand : CliktCommand(name = "login", help = "Sign in to a remote MCP server in the browser") {
    private val server by argument()
    private val mcpConfig by option("--mcp-config", help = "Path to the MCP server config (default: .sophi/mcp.json)").default(".sophi/mcp.json")
    override fun run() {
        val mask = SecretMask()
        val store = KeychainCredentialStore(mask = mask)
        val oauth = McpOAuth(store, mask, OAuthFlow(openBrowser = openInBrowser))
        echo(runBlocking {
            mcpAuth("login", server, Path.of(mcpConfig), login = { c -> oauth.login(resolveSecrets(c, store, mask)).map { emptyList() } }, logout = {})
        })
    }
}

class McpLogoutCommand : CliktCommand(name = "logout", help = "Forget a remote MCP server's sign-in") {
    private val server by argument()
    private val mcpConfig by option("--mcp-config", help = "Path to the MCP server config (default: .sophi/mcp.json)").default(".sophi/mcp.json")
    override fun run() {
        val store = KeychainCredentialStore()
        echo(runBlocking {
            mcpAuth("logout", server, Path.of(mcpConfig), login = { error("unused") },
                logout = { McpOAuth(store, SecretMask(), OAuthFlow(openBrowser = {})).logout(it) })
        })
    }
}
