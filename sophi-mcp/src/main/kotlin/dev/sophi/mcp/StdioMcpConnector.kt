package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A packaged/Dock-launched macOS app inherits a minimal system PATH (no Homebrew, no nvm), so a
 * bare command like "npx" fails to spawn even though it works fine from a terminal. Resolves the
 * user's real PATH by asking their login shell for it, the same way editors like VS Code work
 * around this. Bounded by [timeoutSeconds] so a broken or hanging shell can never stall startup;
 * any failure (missing shell, timeout, non-zero exit) returns null rather than throwing.
 *
 * Must run **interactive** (`-i`), not just login (`-l`): zsh only sources `~/.zshrc` for an
 * interactive shell, and that's where most people's Homebrew/nvm/node PATH exports actually live
 * (`~/.zprofile` is often just `brew shellenv`) — a login-only, non-interactive shell silently
 * skips `.zshrc` and resolves the *wrong* PATH instead of failing, which is worse than not trying
 * at all. Reads only the last non-blank output line so a shell that prints a startup banner before
 * running the command (oh-my-zsh update checks, MOTD) doesn't corrupt the parsed PATH.
 */
internal fun resolveLoginShellPath(shell: String, timeoutSeconds: Long = 5): String? = runCatching {
    val process = ProcessBuilder(shell, "-i", "-l", "-c", "echo -n \$PATH").redirectErrorStream(true).start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    if (process.exitValue() != 0) return@runCatching null
    process.inputStream.bufferedReader().readText()
        .lines().lastOrNull { it.isNotBlank() }?.trim().takeIf { !it.isNullOrBlank() }
}.getOrNull()

/**
 * Resolves a bare command name (e.g. "npx") to an absolute path by searching [path] (`:`-joined,
 * PATH-style) for an executable file, leaving the rest of [command] untouched. Already-qualified
 * commands (containing a `/`) pass through unchanged.
 *
 * A correctly-set `PATH` environment variable is not enough on its own: a packaged app's bundled
 * JVM can fail to find a bare command via its own `PATH`-searching `ProcessBuilder.start()` even
 * when that same `PATH` correctly resolves the command from a plain terminal-launched JVM of the
 * same version — observed in practice with sophi-companion's jpackage-trimmed runtime image.
 * Resolving to an absolute path ourselves sidesteps `ProcessBuilder`'s own PATH search (and
 * whatever makes it unreliable in that environment) entirely, so `execve()` gets a path it can't
 * fail to find.
 */
internal fun resolveExecutable(command: List<String>, path: String?): List<String> {
    val exe = command.firstOrNull() ?: return command
    if (exe.contains('/')) return command
    val resolved = (path ?: System.getenv("PATH") ?: "")
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, exe) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?: return command
    return listOf(resolved.absolutePath) + command.drop(1)
}

/**
 * Spawns the configured command as a subprocess and connects to it over the MCP SDK's stdio
 * transport.
 *
 * Note: the real SDK's [StdioClientTransport] constructor takes `input`/`output` (not
 * `inputStream`/`outputStream`) as its parameter names.
 */
class StdioMcpConnector(
    private val loginShell: String = System.getenv("SHELL") ?: "/bin/zsh",
    /**
     * Bounds the handshake (process spawn through `listTools`-ready). Without this, a server that
     * spawns but never speaks the protocol — e.g. `--user-data-dir` locked by an already-running
     * browser, or a slow first-run `npx -y` package fetch that stalls — hangs the connect call
     * forever: no exception, no log line, no tools ever registered, and no visible sign anything
     * is wrong. 30s covers a cold `npx` fetch; a warm one resolves in well under a second.
     */
    private val connectTimeoutSeconds: Long = 30
) : McpConnector {
    // Computed once and reused for every connect() call: McpClientManager holds one
    // StdioMcpConnector for the whole SophiRuntime lifetime, and the login shell's PATH doesn't
    // change mid-run.
    private val resolvedPath: String? by lazy { resolveLoginShellPath(loginShell) }

    override suspend fun connect(config: McpServerConfig): McpSession {
        require(config.command.isNotEmpty()) { "stdio MCP server '${config.name}' requires a non-empty command" }
        val processBuilder = ProcessBuilder(resolveExecutable(config.command, resolvedPath)).redirectErrorStream(false)
        resolvedPath?.let { processBuilder.environment()["PATH"] = it }
        processBuilder.environment().putAll(config.env)
        // A bare "Cannot run program X: No such file or directory" gives no way to tell, after
        // the fact, whether resolveLoginShellPath found nothing or found a PATH that still
        // doesn't contain X — folding that state into the exception message means the *next*
        // failure report is conclusive instead of needing another round of guess-and-relaunch.
        val process = try {
            processBuilder.start()
        } catch (e: java.io.IOException) {
            throw java.io.IOException(
                "${e.message} [resolveLoginShellPath($loginShell) -> " +
                    (resolvedPath?.let { "\"$it\"" } ?: "null (resolution failed or timed out)") + "]",
                e
            )
        }

        val client = connectOrDestroy(process) {
            try {
                withTimeout(connectTimeoutSeconds * 1000) {
                    val transport = StdioClientTransport(
                        input = process.inputStream.asSource().buffered(),
                        output = process.outputStream.asSink().buffered()
                    )
                    val client = Client(clientInfo = Implementation(name = "sophi", version = "1.0.0"))
                    client.connect(transport)
                    client
                }
            } catch (e: TimeoutCancellationException) {
                throw McpConnectTimeoutException(config.name, connectTimeoutSeconds)
            }
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

/** Thrown when a stdio MCP server's handshake doesn't finish within [StdioMcpConnector]'s timeout. */
class McpConnectTimeoutException(serverName: String, timeoutSeconds: Long) :
    Exception("MCP server '$serverName' did not complete its handshake within ${timeoutSeconds}s")
