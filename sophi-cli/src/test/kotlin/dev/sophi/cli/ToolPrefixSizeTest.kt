package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeLessThan
import io.mockk.mockk
import kotlinx.serialization.json.Json

/**
 * Every request carries the tool schemas, so on a small local model (a 32k window) their size matters.
 * Builds the tool set an interactive `sophi` session sends, prints what it costs and fails if it grows past the ceiling:
 * raise the ceiling on purpose, never by accident.
 */
class ToolPrefixSizeTest : FunSpec({
    // 18,674 chars when set (A11), plus ~10% headroom. Counts name + description + schema JSON per tool, `skill` excluded.
    val ceilingChars = 20_500

    test("the tool prefix a session sends stays under its ceiling") {
        val dir = tempdir().toPath()
        val cli = autoClose(buildCliRuntime(
            optionsFor(dir), mockk<LLMProvider>(relaxed = true), Terminal(), ScriptedInputSource(emptyList()), interactive = true,
        ))
        val registry = cli.registry
        // Providers parse the schema and send it compact (OpenAICompatProvider.toFunctionParameters), so indentation is free.
        fun size(name: String, description: String, schema: String) =
            name.length + description.length + Json.parseToJsonElement(schema).toString().length
        val sent = registry.definitions().map { it.name to size(it.name, it.description, it.parametersJson) }
        val deferred = registry.deferredNames().map { registry.get(it) }.map { it.name to size(it.name, it.description, it.parametersJson) }
        val total = sent.sumOf { it.second }
        // `skill` lists the skills installed on this machine (~/.sophi, ~/.claude), so it is reported, not held to the ceiling.
        val skills = sent.single { it.first == "skill" }.second
        // chars/4 is a rough token estimate; no tokenizer dependency for a ceiling check.
        println("tool prefix: ${sent.size} tools, $total chars, ~${total / 4} tokens (skill list here: $skills chars)")
        println("deferred (behind tool_search, not in the prompt): ${deferred.size} tools, ${deferred.sumOf { it.second }} chars")
        sent.sortedByDescending { it.second }.take(10).forEach { (name, chars) -> println("  %-28s %6d chars  ~%5d tokens".format(name, chars, chars / 4)) }
        total - skills shouldBeLessThan ceilingChars
    }
})
