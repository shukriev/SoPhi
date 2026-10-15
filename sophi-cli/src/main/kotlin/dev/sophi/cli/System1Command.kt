package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.system1.JudgmentSpec
import dev.sophi.memory.jane.EncoderJudgment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Every judgment that has a spec; add one here as its seeds land (ADR-042 addendum). */
internal val judgmentSpecs: List<JudgmentSpec> = listOf(EncoderJudgment)

private val lineJson = Json { ignoreUnknownKeys = true }

/** Renders and parses System-1 judgments line by line, for the out-of-tree evaluation harness. */
class System1Batch(private val specs: List<JudgmentSpec> = judgmentSpecs, private val echo: (String) -> Unit) {

    fun render(judgment: String, model: String, lines: Sequence<String>) {
        val spec = spec(judgment)
        each(lines) { id, row ->
            val request = spec.render(row.getValue("input").toString(), model)
            buildJsonObject { put("id", id); put("request", request.toJson()) }
        }
    }

    fun parse(judgment: String, lines: Sequence<String>) {
        val spec = spec(judgment)
        each(lines) { id, row ->
            val output = spec.parse(row["reply"]?.jsonPrimitive?.contentOrNull ?: "")
            buildJsonObject { put("id", id); put("output", output?.let { lineJson.parseToJsonElement(it) } ?: JsonNull) }
        }
    }

    private fun spec(name: String): JudgmentSpec = specs.firstOrNull { it.name == name }
        ?: throw UsageError("unknown judgment '$name' (known: ${specs.joinToString { it.name }})")

    // One bad line must not cost the rest of the batch: it becomes an error line with its id.
    private fun each(lines: Sequence<String>, handle: (String, JsonObject) -> JsonObject) {
        lines.filter { it.isNotBlank() }.forEach { line ->
            val row = runCatching { lineJson.parseToJsonElement(line).jsonObject }.getOrNull()
            val id = row?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
            val out = if (row == null) errorLine(id, "not a JSON object")
                else runCatching { handle(id, row) }.getOrElse { errorLine(id, it.message ?: it::class.simpleName ?: "error") }
            echo(out.toString())
        }
    }

    private fun errorLine(id: String, message: String) = buildJsonObject { put("id", id); put("error", message) }
}

private fun CompletionRequest.toJson() = buildJsonObject {
    put("systemPrompt", systemPrompt)
    putJsonArray("messages") {
        messages.forEach { m -> addJsonObject { put("role", m.role.name.lowercase()); put("content", m.content) } }
    }
    put("maxTokens", maxTokens)
    put("temperature", temperature)
    put("reasoningEffort", reasoningEffort)
    put("judgment", judgment)
}

class System1Command : CliktCommand(name = "system1", help = "Render and parse System-1 judgments for evaluation (ADR-042)") {
    override fun run() = Unit
}

class System1RenderCommand : CliktCommand(name = "render", help = "stdin {id,input} JSONL -> stdout {id,request} JSONL") {
    private val judgment by option("--judgment").required()
    private val model by option("--model").default("system1")
    override fun run() = System1Batch { echo(it) }.render(judgment, model, generateSequence { readlnOrNull() })
}

class System1ParseCommand : CliktCommand(name = "parse", help = "stdin {id,reply} JSONL -> stdout {id,output} JSONL") {
    private val judgment by option("--judgment").required()
    override fun run() = System1Batch { echo(it) }.parse(judgment, generateSequence { readlnOrNull() })
}
