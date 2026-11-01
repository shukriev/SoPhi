package dev.sophi.mcp.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A server listing more tools than this keeps them out of the prompt; the model finds them with tool_search. */
const val DEFAULT_DEFER_TOOLS_OVER = 10

@Serializable
enum class McpTransport {
    @SerialName("stdio") STDIO,
    @SerialName("http") HTTP
}

@Serializable
data class McpServerConfig(
    val name: String,
    val transport: McpTransport,
    val command: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    /** http only: sent on every request. Values may be `${secret:name}` references. */
    val headers: Map<String, String> = emptyMap(),
    /** http only: a registered OAuth client, for servers without dynamic registration (GitHub). */
    val oauth: McpOAuthConfig? = null,
    val safeTools: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class McpOAuthConfig(val clientId: String, val clientSecret: String? = null, val scopes: List<String> = emptyList())

@Serializable
data class McpConfig(
    val servers: List<McpServerConfig> = emptyList(),
    val deferToolsOver: Int = DEFAULT_DEFER_TOOLS_OVER
)
