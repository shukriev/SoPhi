package dev.sophi.sdk

import dev.sophi.core.credentials.KeychainCredentialStore
import dev.sophi.core.credentials.OAuthFlow
import dev.sophi.core.credentials.SecretMask
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.StreamableHttpMcpConnector
import dev.sophi.mcp.auth.McpOAuth
import dev.sophi.mcp.auth.signInRequired
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.skills.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** One line of `sophi doctor`: what was checked, whether it's fine, and for a failure how to fix it. */
data class DoctorCheck(val name: String, val ok: Boolean, val detail: String, val fix: String? = null)

/** Pinned skills whose files no longer match their install pin: `sophi` leaves them out. */
fun checkSkillPins(globalDir: Path, projectDir: Path): List<DoctorCheck> {
    val refused = SkillRegistry.load(globalDir, projectDir).refused
    if (refused.isEmpty()) return listOf(DoctorCheck("skills", true, "every pinned skill matches its install"))
    return refused.map { DoctorCheck("skills", false, it, "reinstall it with `sophi skill install --update`, or remove it") }
}

/**
 * Connects each enabled server alone through [connect] (its tool count back) within [timeout]. A connect that
 * ignores cancellation is abandoned, not waited for.
 */
suspend fun checkMcpServers(
    configs: List<McpServerConfig>,
    configPath: Path,
    connect: suspend (McpServerConfig) -> Int,
    timeout: Duration,
): List<DoctorCheck> {
    val enabled = configs.filter { it.enabled }
    if (enabled.isEmpty()) return listOf(DoctorCheck("mcp", true, "no MCP servers configured"))
    return enabled.map { c ->
        val name = "mcp ${c.name}"
        // ponytail: an abandoned blocking connect keeps its IO thread until the process exits; doctor exits right after.
        val attempt = CoroutineScope(Dispatchers.IO).async { runCatching { connect(c) } }
        val result = withTimeoutOrNull(timeout) { attempt.await() }
        when {
            result == null -> {
                attempt.cancel()
                DoctorCheck(name, false, "timed out after $timeout", "check the command in $configPath")
            }
            result.isSuccess -> DoctorCheck(name, true, "${result.getOrThrow()} tools")
            else -> {
                val e = result.exceptionOrNull()!!
                val signIn = e.signInRequired()
                DoctorCheck(name, false, e.message ?: e.toString(),
                    if (signIn != null) "sophi mcp login ${c.name}" else "check the command in $configPath")
            }
        }
    }
}

/**
 * The connect `sophi` itself makes (Keychain secrets, stored sign-ins), for [checkMcpServers]: connects, counts
 * the tools, disconnects. Never opens a browser.
 */
fun mcpToolCounter(): suspend (McpServerConfig) -> Int {
    val mask = SecretMask()
    val store = KeychainCredentialStore(mask = mask)
    val oauth = McpOAuth(store, mask, OAuthFlow(openBrowser = { error("doctor never signs in") }))
    val manager = McpClientManager(httpConnector = StreamableHttpMcpConnector(auth = oauth), credentials = store, mask = mask)
    return { config ->
        var failure: Throwable? = null
        val tools = manager.connectOne(config, onFailure = { failure = it })
        failure?.let { throw it }
        manager.disconnect(config.name)
        tools.size
    }
}

/**
 * Runs [command] for up to [timeout]. Missing, non-zero or too slow: a failure with [hint] as its fix when
 * [required], else ok with [hint] as information.
 */
fun checkCommand(name: String, command: List<String>, timeout: Duration, required: Boolean, hint: String): DoctorCheck {
    val ok = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS).also { done -> if (!done) p.destroyForcibly() } && p.exitValue() == 0
    } catch (e: IOException) {
        false
    }
    return when {
        ok -> DoctorCheck(name, true, "`${command.joinToString(" ")}` works")
        required -> DoctorCheck(name, false, "`${command.joinToString(" ")}` failed", hint)
        else -> DoctorCheck(name, true, "not found: $hint")
    }
}
