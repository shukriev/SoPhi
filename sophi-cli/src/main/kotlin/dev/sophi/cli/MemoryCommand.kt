package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.ForgetRequest
import dev.sophi.memory.ProfileAction
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import kotlinx.coroutines.runBlocking

/**
 * `sophi memory` — inspect and control Jane's Theory memory (spec §9). Operates directly
 * on ~/.sophi/memory/; needs no running chat session. Providers are wired only for the
 * subcommands that need them (search, consolidation compression).
 */
class MemoryCommand : CliktCommand(name = "memory", help = "Browse and control Sophi's long-term memory") {
    init {
        subcommands(MemoryList(), MemoryShow(), MemoryThreads(), MemoryProfile(),
            MemoryForget(), MemoryWhy(), MemoryConsolidate(), MemoryRestore(), MemoryReset())
    }
    override fun run() = Unit
}

internal fun palace(
    baseUrl: String? = null, apiKey: String? = null,
    embeddingModel: String? = null, dimensions: Int = 1536, chatModel: String? = null
): JanesPalace {
    val emb = if (baseUrl != null && embeddingModel != null)
        buildOpenAiCompatEmbeddingProvider(baseUrl, apiKey, embeddingModel, dimensions) else null
    val llm = if (baseUrl != null && chatModel != null)
        buildProvider("openai-compat", apiKey, baseUrl, chatModel, 60L, 2) else null
    return JanesPalace(
        JanesPalaceConfig(sessionModel = chatModel, autoPurgeEnabled = JanesPalaceConfig.autoPurgeEnabledFromEnv()),
        llm, emb, embeddingModel ?: "unknown"
    )
}

private fun renderView(v: dev.sophi.memory.MemoryView): String {
    val m = v.metadata
    return "[${v.id}] (${m["room"]}, sal ${m["salience"]}, pri ${m["priority"]}, ${m["ageDays"]}d, " +
        "${m["sensitivity"]}, ${m["state"]}) ${v.text}"
}

class MemoryList : CliktCommand(name = "list", help = "Browse memories by room") {
    private val room: String? by option("--room", help = "entities|tasks|episodes|knowledge|narrative")
    private val all: Boolean by option("--all", help = "Include superseded and soft-deleted").flag()
    override fun run() {
        val views = palace().browse(BrowseFilter(room = room, includeHidden = all))
        if (views.isEmpty()) echo("(no memories)") else views.forEach { echo(renderView(it)) }
    }
}

class MemoryShow : CliktCommand(name = "show", help = "Show one memory in full") {
    private val id: String by argument()
    override fun run() {
        val v = palace().browse(BrowseFilter(includeHidden = true)).firstOrNull { it.id == id }
            ?: return echo("Not found: $id")
        echo(renderView(v))
        v.metadata.forEach { (k, value) -> echo("  $k = $value") }
    }
}

class MemoryThreads : CliktCommand(name = "threads", help = "Narrative threads as story lines") {
    override fun run() {
        val threads = palace().threads()
        if (threads.isEmpty()) return echo("(no threads)")
        threads.forEach { (label, texts) -> echo("[$label] " + texts.joinToString(" -> ")) }
    }
}

class MemoryProfile : CliktCommand(
    name = "profile",
    help = "Show the user model; optionally confirm/correct/delete an attribute"
) {
    private val action: String? by argument(help = "confirm|correct|delete").optional()
    private val path: String? by argument(help = "attribute path").optional()
    private val value: String? by argument(help = "new value (correct only)").optional()
    override fun run() {
        val p = palace()
        when (action) {
            null -> p.profileView().ifEmpty { return echo("(empty profile)") }
                .forEach { echo("${it.path} = ${it.value} (%.2f)".format(it.confidence)) }
            "confirm" -> {
                val p2 = path ?: return echo("Usage: sophi memory profile confirm <path>")
                echo(if (p.updateProfile(ProfileAction.Confirm(p2))) "Confirmed." else "No such attribute.")
            }
            "correct" -> {
                val p2 = path ?: return echo("Usage: sophi memory profile correct <path> <value>")
                val v2 = value ?: return echo("Usage: sophi memory profile correct <path> <value>")
                echo(if (p.updateProfile(ProfileAction.Correct(p2, v2))) "Corrected." else "No such attribute.")
            }
            "delete" -> {
                val p2 = path ?: return echo("Usage: sophi memory profile delete <path>")
                echo(if (p.updateProfile(ProfileAction.Delete(p2))) "Deleted." else "No such attribute.")
            }
            else -> echo("Unknown action: $action (use confirm|correct|delete)")
        }
    }
}

class MemoryForget : CliktCommand(name = "forget", help = "Hard-delete a memory — provably gone") {
    private val id: String? by argument(help = "memory id").optional()
    private val about: String? by option("--about", help = "Find candidates semantically instead of by id")
    private val baseUrl: String? by option("--base-url")
    private val apiKey: String? by option("--api-key")
    private val embeddingModel: String? by option("--embedding-model")
    private val dimensions: Int by option("--embedding-dimensions").int().default(1536)
    private val yes: Boolean by option("--yes", help = "Skip confirmation").flag()
    override fun run() = runBlocking {
        val p = palace(baseUrl, apiKey, embeddingModel, dimensions)
        val targetId = when {
            id != null -> id!!
            about != null -> {
                val hits = p.search(about!!, 5)
                if (hits.isEmpty()) return@runBlocking echo(
                    "No matches (search needs --base-url and --embedding-model).")
                hits.forEachIndexed { i, v -> echo("${i + 1}. ${renderView(v)}") }
                echo("Re-run with: sophi memory forget <id>")
                return@runBlocking
            }
            else -> return@runBlocking echo("Give a memory id or --about \"<query>\".")
        }
        val victim = p.browse(BrowseFilter(includeHidden = true)).firstOrNull { it.id == targetId }
            ?: return@runBlocking echo("Not found: $targetId")
        echo("Will permanently delete: ${renderView(victim)}")
        val preview = p.previewForget(targetId)
        if (preview.affectedProfilePaths.isNotEmpty())
            echo("Also reduces profile attributes: ${preview.affectedProfilePaths.joinToString()}")
        if (preview.relinkedEdges > 0)
            echo("Re-links ${preview.relinkedEdges} causal edge(s) around the gap.")
        if (!yes && !confirm("Proceed?")) return@runBlocking echo("Aborted.")
        val result = p.forget(ForgetRequest.ById(targetId))
        echo("Deleted ${result.removedIds.size} memor${if (result.removedIds.size == 1) "y" else "ies"}; " +
            "re-linked ${result.relinkedEdges} edge(s); " +
            "affected profile: ${result.affectedProfilePaths.ifEmpty { listOf("none") }.joinToString()}")
    }
    private fun confirm(prompt: String): Boolean {
        echo("$prompt [y/N] ", trailingNewline = false)
        return readLine()?.trim()?.lowercase() == "y"
    }
}

class MemoryWhy : CliktCommand(name = "why", help = "Explain the last memory injection") {
    override fun run() {
        echo(palace().explainLastRecall() ?: "(no recall recorded yet)")
    }
}

class MemoryConsolidate : CliktCommand(name = "consolidate", help = "Run the sleep cycle now") {
    private val baseUrl: String? by option("--base-url", help = "Chat endpoint for thread compression (optional)")
    private val apiKey: String? by option("--api-key")
    private val model: String? by option("--model", help = "Chat model for compression (optional)")
    private val yes: Boolean by option("--yes", help = "Skip confirmation").flag()
    override fun run() = runBlocking {
        if (!yes && !confirm("Run the sleep cycle now?")) return@runBlocking echo("Aborted.")
        val report = palace(baseUrl, apiKey, chatModel = model).consolidate(System.currentTimeMillis())
        echo("merged=${report.merged} strengthened=${report.strengthened} compressed=${report.compressed} " +
            "pruned=${report.pruned} purged=${report.purged}")
        if (model == null) echo("(compression skipped — pass --base-url and --model to enable)")
    }
    private fun confirm(prompt: String): Boolean {
        echo("$prompt [y/N] ", trailingNewline = false)
        return readLine()?.trim()?.lowercase() == "y"
    }
}

class MemoryRestore : CliktCommand(name = "restore", help = "Undo a soft-delete before it's purged") {
    private val id: String by argument(help = "memory id")
    override fun run() = runBlocking {
        val restored = palace().restore(id)
        echo(if (restored) "Restored: $id" else "Not found or not soft-deleted: $id")
    }
}

class MemoryReset : CliktCommand(name = "reset", help = "Wipe ALL memory — irreversible") {
    private val yes: Boolean by option("--yes").flag()
    override fun run() = runBlocking {
        echo("This wipes ~/.sophi/memory entirely — every memory, thread, and profile attribute.")
        if (!yes) {
            echo("Type RESET to confirm: ", trailingNewline = false)
            if (readLine()?.trim() != "RESET") return@runBlocking echo("Aborted.")
        }
        val result = palace().forget(ForgetRequest.All)
        echo("Wiped ${result.removedIds.size} memories.")
    }
}
