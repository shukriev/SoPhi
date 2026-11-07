package dev.sophi.core.tools

import dev.sophi.ai.providers.buildProviderFromType
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * The live false-positive / miss measurement over evals/read-check. Runs only when SOPHI_READ_CHECK_BASE_URL (an
 * OpenAI-compatible local server) and SOPHI_READ_CHECK_MODEL are set; prints the rates for RESULTS.md, asserts nothing.
 */
class ReadCheckEvalTest : FunSpec({
    val baseUrl = System.getenv("SOPHI_READ_CHECK_BASE_URL")
    val model = System.getenv("SOPHI_READ_CHECK_MODEL")

    test("read check over evals/read-check").config(enabled = baseUrl != null && model != null) {
        val provider = buildProviderFromType("local", System.getenv("SOPHI_READ_CHECK_API_KEY"), baseUrl, model!!)
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve("evals/read-check") }.first { Files.isDirectory(it) }
        val ms = mutableListOf<Long>()
        for (set in listOf("benign", "injected", "agent-instructions")) {
            val counts = mutableMapOf<ReadVerdict.Label, Int>()
            Files.list(root.resolve(set)).use { it.sorted().toList() }.forEach { file ->
                // A fresh guardian per sample: no cache hits between samples.
                val start = System.currentTimeMillis()
                val v = LlmGuardian(provider, model, 120.seconds, reads = GateMode.SHADOW).checkRead(file.readText(), "Read this page for me.")
                ms += System.currentTimeMillis() - start
                counts.merge(v.label, 1, Int::plus)
                println("$set/${file.name}: ${v.label.name.lowercase()} ${v.reason}")
            }
            val n = counts.values.sum()
            val wrong = if (set == "benign") counts[ReadVerdict.Label.FLAG] ?: 0 else counts[ReadVerdict.Label.CLEAN] ?: 0
            println("$set: n=$n ${if (set == "benign") "false positives" else "misses"}=$wrong (${100 * wrong / n.coerceAtLeast(1)}%) failed=${counts[ReadVerdict.Label.FAILED] ?: 0}")
        }
        println("model=$model median ms=${ms.sorted()[ms.size / 2]}")
    }
})
