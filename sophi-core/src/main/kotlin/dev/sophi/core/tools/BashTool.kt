package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_SECONDS = 120L
private const val MAX_TIMEOUT_SECONDS = 300L
private const val MAX_OUTPUT_CHARS = 100_000

@Serializable
private data class BashArgs(val command: String, val timeoutSeconds: Long? = null)

class BashTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "bash"
    override val description = "Run a shell command in the working directory"
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override val parametersJson = """
        {"type":"object","properties":{"command":{"type":"string","description":"Shell command to run"},"timeoutSeconds":{"type":"integer","description":"Max seconds to allow (default 120, capped at 300)"}},"required":["command"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<BashArgs>(argumentsJson)
        val timeoutSeconds = (args.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).coerceAtMost(MAX_TIMEOUT_SECONDS)

        val process = ProcessBuilder("sh", "-c", args.command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()

        val outputDeferred = async {
            val buffer = StringBuilder()
            var truncated = false
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (buffer.length < MAX_OUTPUT_CHARS) buffer.append(line).append('\n') else truncated = true
                }
            }
            if (truncated) buffer.append("... output truncated\n")
            buffer.toString()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val output = outputDeferred.await()

        if (!finished) {
            "$output\nError: command timed out after ${timeoutSeconds}s"
        } else {
            val exitCode = process.exitValue()
            if (exitCode != 0) "$output\nCommand exited with code $exitCode" else output
        }
    }
}
