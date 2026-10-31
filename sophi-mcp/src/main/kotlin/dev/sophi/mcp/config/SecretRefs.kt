package dev.sophi.mcp.config

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.SecretMask

private val REF = Regex("""\$\{secret:([A-Za-z0-9_.-]+)}""")

/** A `${secret:name}` that the store doesn't hold. Names the secret, never a value. */
class MissingSecretException(val secretName: String, server: String) :
    Exception("MCP server '$server' needs the secret '$secretName' — set it with `sophi secret set $secretName`")

/**
 * [config] with every `${secret:name}` in `env`, `headers` and `oauth.clientSecret` replaced from [store]. Each value
 * joins [mask]. A missing one fails the whole server, so the literal reference is never sent.
 */
fun resolveSecrets(config: McpServerConfig, store: CredentialStore?, mask: SecretMask): McpServerConfig {
    fun resolve(value: String) = REF.replace(value) { m ->
        val name = m.groupValues[1]
        val secret = store?.get(name) ?: throw MissingSecretException(name, config.name)
        mask.add(secret)
        secret
    }
    return config.copy(
        env = config.env.mapValues { resolve(it.value) },
        headers = config.headers.mapValues { resolve(it.value) },
        oauth = config.oauth?.let { o -> o.copy(clientSecret = o.clientSecret?.let(::resolve)) },
    )
}
