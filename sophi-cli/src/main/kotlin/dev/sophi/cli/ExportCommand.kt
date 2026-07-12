package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.learning.export.ExportInputs
import dev.sophi.learning.export.ExportOptions
import dev.sophi.learning.export.Exporter
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

class ExportRunner(
    private val learningHome: Path,
    private val sessionsDir: Path,
    private val options: ExportOptions,
    private val echo: (String) -> Unit
) {
    fun run() {
        val result = Exporter(ExportInputs(learningHome, sessionsDir),
            userPatternsFile = learningHome.resolve("redaction.txt")).export(options)
        echo("sft: ${result.sftExamples} examples")
        echo("dpo: ${result.dpoPairs} pairs")
        echo("manifest: ${result.manifestPath}")
    }
}

class ExportCommand : CliktCommand(name = "export", help = "Export sessions as fine-tuning datasets") {
    private val out by option("--out").default("./sophi-export-${LocalDate.now()}")
    private val scope by option("--scope")
    private val since by option("--since", help = "ISO date, e.g. 2026-01-01")
    private val minJudgment by option("--min-judgment").default("success")
    private val perTurn by option("--per-turn").flag()
    private val split by option("--split")
    private val noRedact by option("--no-redact").flag()
    private val force by option("--force").flag()
    private val learningHomeStr by option("--learning-home")
        .default("${System.getProperty("user.home")}/.sophi/learning")
    private val sessionsDirStr by option("--sessions-dir")
        .default("${System.getProperty("user.home")}/.sophi/sessions")

    override fun run() {
        if (noRedact && !force) {
            echo("Really export without redaction? [y/N] ", trailingNewline = false)
            if (readlnOrNull()?.trim()?.lowercase() != "y") { echo("Aborted."); return }
        }
        ExportRunner(
            Path.of(learningHomeStr), Path.of(sessionsDirStr),
            ExportOptions(
                outDir = Path.of(out), scope = scope,
                since = since?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
                minJudgment = minJudgment, perTurn = perTurn,
                split = split?.toDouble(), redact = !noRedact, force = force
            )
        ) { echo(it) }.run()
    }
}
