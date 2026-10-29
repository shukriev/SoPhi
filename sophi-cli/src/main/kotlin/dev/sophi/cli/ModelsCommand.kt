package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.sophi.sdk.ModelCatalog
import dev.sophi.sdk.ModelInfo
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.zip.GZIPOutputStream

val DEFAULT_MODELS_CACHE: Path = Path.of(System.getProperty("user.home"), ".sophi", "models.json")
val DEFAULT_MODELS_USER: Path = Path.of(System.getProperty("user.home"), ".sophi", "models.local.json")

/** The catalog every `sophi` command reads: the refreshed cache (or the bundled snapshot) with the user's file over it. */
fun loadModelCatalog(onWarning: (String) -> Unit = { System.err.println(it) }): ModelCatalog =
    ModelCatalog.load(DEFAULT_MODELS_CACHE, DEFAULT_MODELS_USER, onWarning)

private const val MAX_ROWS = 50

class ModelsCommand(private val catalog: () -> ModelCatalog = { loadModelCatalog() }) : CliktCommand(
    name = "models",
    help = "Look up models in the catalog (window, price per 1M tokens, capabilities). " +
        "It refreshes itself from models.dev once a day; ~/.sophi/models.local.json overrides entries."
) {
    private val query by argument(help = "Part of a provider/model id, e.g. claude or qwen3-8b").optional()
    private val refresh by option("--refresh", help = "Download the catalog from models.dev now").flag()
    private val out by option("--out", help = "With --refresh: write a gzipped snapshot here instead (how the bundled one is made)").path()

    override fun run() {
        if (refresh) return refreshNow()
        val hits = catalog().search(query ?: "")
        hits.take(MAX_ROWS).forEach { echo(row(it)) }
        if (hits.size > MAX_ROWS) echo("… ${hits.size - MAX_ROWS} more; narrow the query")
        if (hits.isEmpty()) echo("No model matches \"${query ?: ""}\".")
    }

    private fun refreshNow() {
        val target = out
        if (target != null) {
            val bytes = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(ModelCatalog.trim(ModelCatalog.fetchModelsDev()).toByteArray()) } }
            ModelCatalog.writeAtomically(target, bytes.toByteArray())
            echo("Wrote ${ModelCatalog.bundledSize(target)} models to $target")
            return
        }
        if (!ModelCatalog.refreshIfStale(DEFAULT_MODELS_CACHE, maxAge = Duration.ZERO)) {
            echo("Couldn't download ${ModelCatalog.SOURCE_URL}; keeping the catalog Sophi has.", err = true)
            return
        }
        echo("Wrote ${loadModelCatalog().size()} models to $DEFAULT_MODELS_CACHE")
    }

    private fun row(m: ModelInfo): String {
        fun usd(v: Double?) = v?.let { String.format(Locale.ROOT, "$%.2f", it) } ?: "-"
        val caps = listOfNotNull("tools".takeIf { m.tools }, "reasoning".takeIf { m.reasoning }, "vision".takeIf { m.vision }).joinToString(" ")
        return "${"${m.provider}/${m.id}".padEnd(48)} ${(m.contextTokens?.toString() ?: "-").padStart(8)}  " +
            "${"${usd(m.inputUsdPerM)}/${usd(m.outputUsdPerM)}".padEnd(16)} $caps".trimEnd()
    }
}
