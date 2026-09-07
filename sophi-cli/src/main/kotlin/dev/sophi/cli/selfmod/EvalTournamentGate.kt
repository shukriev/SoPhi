package dev.sophi.cli.selfmod

import dev.sophi.core.agent.eval.loadEvalCases
import dev.sophi.sdk.TournamentResult
import dev.sophi.sdk.evaluateAcceptance
import java.nio.file.Files
import java.nio.file.Path

data class EvalGateOutcome(
    val skipped: Boolean,
    val skipReason: String? = null,
    val result: TournamentResult? = null,
    val baselineScores: Map<String, List<Double>> = emptyMap(),
    val challengerScores: Map<String, List<Double>> = emptyMap()
)

private val HEADLINE_REGEX = Regex("""headline=([0-9.]+)""")
private val CATEGORY_REGEX = Regex("""^ {2}(\S+): ([0-9.]+)$""", RegexOption.MULTILINE)

private fun parseScores(stdout: String): Pair<Double, Map<String, Double>> {
    val headline = HEADLINE_REGEX.find(stdout)?.groupValues?.get(1)?.toDouble()
        ?: error("could not parse a headline score from eval-run output: $stdout")
    val categories = CATEGORY_REGEX.findAll(stdout).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
    return headline to categories
}

private fun runOnce(
    jar: Path, evalsDir: Path, configVersionId: String, versioningHome: Path,
    model: String, providerType: String, apiKeyOption: String?, baseUrl: String?, javaCommand: List<String>
): Pair<Double, Map<String, Double>> {
    val sessionsDir = Files.createTempDirectory("sophi-selfmod-eval-sessions-")
    val args = buildList {
        addAll(javaCommand); add("-jar"); add(jar.toString())
        add("evals"); add("run")
        add("--evals-dir"); add(evalsDir.toString())
        add("--config-version"); add(configVersionId)
        add("--versioning-home"); add(versioningHome.toString())
        add("--sessions-dir"); add(sessionsDir.toString())
        add("--model"); add(model)
        add("--provider"); add(providerType)
        apiKeyOption?.let { add("--api-key"); add(it) }
        baseUrl?.let { add("--base-url"); add(it) }
    }
    val process = ProcessBuilder(args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "eval-run failed: $output" }
    return parseScores(output)
}

private fun accumulate(target: MutableMap<String, MutableList<Double>>, headline: Double, categories: Map<String, Double>) {
    target.getOrPut("overall") { mutableListOf() }.add(headline)
    categories.forEach { (cat, score) -> target.getOrPut(cat) { mutableListOf() }.add(score) }
}

fun runEvalGate(
    baselineJar: Path,
    challengerJar: Path,
    evalsDir: Path,
    configVersionId: String,
    versioningHome: Path,
    model: String,
    providerType: String,
    apiKeyOption: String?,
    baseUrl: String?,
    runsPerSide: Int = 3,
    javaCommand: List<String> = listOf("java")
): EvalGateOutcome {
    if (loadEvalCases(evalsDir).isEmpty()) {
        return EvalGateOutcome(skipped = true, skipReason = "no eval cases found under $evalsDir")
    }

    val baselineScores = mutableMapOf<String, MutableList<Double>>()
    val challengerScores = mutableMapOf<String, MutableList<Double>>()
    repeat(runsPerSide) {
        val (bh, bc) = runOnce(baselineJar, evalsDir, configVersionId, versioningHome, model, providerType, apiKeyOption, baseUrl, javaCommand)
        accumulate(baselineScores, bh, bc)
        val (ch, cc) = runOnce(challengerJar, evalsDir, configVersionId, versioningHome, model, providerType, apiKeyOption, baseUrl, javaCommand)
        accumulate(challengerScores, ch, cc)
    }

    return EvalGateOutcome(
        skipped = false,
        result = evaluateAcceptance(baselineScores, challengerScores),
        baselineScores = baselineScores, challengerScores = challengerScores
    )
}
