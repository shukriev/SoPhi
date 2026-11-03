package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.ai.providers.LocalModelInstaller
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.LocalServerKind
import dev.sophi.ai.providers.PinnedFile
import dev.sophi.ai.providers.discoverLocalServer
import dev.sophi.ai.providers.llamaCppEngineFor
import dev.sophi.ai.providers.unsupportedPlatformMessage
import java.io.IOException
import java.util.Locale

private fun size(bytes: Long): String =
    if (bytes < 1L shl 30) "${Math.round(bytes.toDouble() / (1L shl 20))} MB"
    else String.format(Locale.ROOT, "%.1f GB", bytes.toDouble() / (1L shl 30))

/** `sophi setup local`: installs llama.cpp and the reference model after the user says yes. [engine] is null on an unsupported platform. */
class SetupLocal(
    private val discover: () -> LocalServer?,
    private val engine: PinnedFile?,
    private val installer: (PinnedFile) -> LocalModelInstaller,
    private val confirm: (String) -> Boolean,
    private val echo: (String) -> Unit,
    private val force: Boolean = false,
) {
    fun run() {
        if (!force) discover()?.let { server ->
            val name = when (server.kind) {
                LocalServerKind.LLAMA_CPP -> "llama.cpp"
                LocalServerKind.LM_STUDIO -> "LM Studio"
                LocalServerKind.OLLAMA -> "Ollama"
                null -> "A model server"
            }
            echo("$name is already running at ${server.baseUrl}; `sophi` uses it as is, nothing to install.")
            echo("Pass --force to install llama.cpp and the reference model anyway.")
            return
        }
        val engine = engine ?: return echo(unsupportedPlatformMessage())
        val local = installer(engine)
        if (local.isInstalled()) echo("llama.cpp and the reference model are already in ${local.installDir}.")
        else {
            echo("This downloads into ${local.installDir}:")
            echo("  llama.cpp: ${engine.fileName} (${size(engine.sizeBytes)})")
            echo("  reference model: ${local.model.fileName} (${size(local.model.sizeBytes)})")
            if (!confirm("Download now? [y/N] ")) return echo("Nothing downloaded.")
            try {
                local.install()
            } catch (e: IOException) {
                echo(e.message ?: e.toString())
                return echo("Run `sophi setup local` again to resume.")
            }
            echo("Installed.")
        }
        echo("Start it with:")
        echo("  " + local.serverCommand().joinToString(" ") { if (' ' in it) "'$it'" else it })
        echo("Then run `sophi` in another terminal: it finds llama.cpp on :${LocalServerKind.LLAMA_CPP.defaultPort} by itself.")
    }
}

class SetupCommand : CliktCommand(name = "setup", help = "Optional one-time setup helpers") {
    override fun run() = Unit
}

class SetupLocalCommand : CliktCommand(
    name = "local",
    help = "Download llama.cpp and the reference model (Qwen3-8B) into ~/.sophi, for machines with no model server",
) {
    private val force by option("--force", help = "Install even when a local model server is already running").flag()

    override fun run() {
        var lastPercent = -1L
        SetupLocal(
            discover = { discoverLocalServer() },
            engine = llamaCppEngineFor(),
            installer = { engine ->
                LocalModelInstaller(engine, onProgress = { label, done, total ->
                    val percent = if (total > 0) done * 100 / total else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        print("\r$label: ${if (percent >= 0) "$percent%" else "${done shr 20} MB"}")
                        if (percent == 100L) println()
                    }
                })
            },
            confirm = { prompt -> print(prompt); readLine()?.trim()?.lowercase() in setOf("y", "yes") },
            echo = { echo(it) },
            force = force,
        ).run()
    }
}
