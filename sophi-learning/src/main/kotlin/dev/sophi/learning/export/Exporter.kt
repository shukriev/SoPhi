package dev.sophi.learning.export

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

data class ExportOptions(
    val outDir: Path,
    val scope: String? = null,            // null = all scopes
    val since: Long? = null,              // epoch millis
    val minJudgment: String = "success",  // "success" | "partial"
    val perTurn: Boolean = false,
    val split: Double? = null,            // e.g. 0.9
    val redact: Boolean = true,
    val force: Boolean = false
)

data class ExportResult(val sftExamples: Int, val dpoPairs: Int, val manifestPath: Path)

/**
 * Orchestrates the offline export pipeline (Phase 4): filters sessions by
 * eligibility, builds SFT/DPO training examples, dedups by content hash,
 * optionally splits train/eval by session id, and writes a provenance
 * manifest. Pure batch job over the phase-1/phase-2/phase-3 logs — no
 * LLM/provider imports, per ADR-012.
 */
class Exporter(private val inputs: ExportInputs, private val userPatternsFile: Path? = null) {

    /**
     * SFT eligibility applies the judgment/negative/subagent filters (plus scope+since).
     * The DPO stream applies scope+since only, by design: DPO pairs come precisely
     * from sessions with an explicit negative preference, so [ExportOptions.minJudgment]
     * does not apply to it.
     */
    fun export(o: ExportOptions): ExportResult {
        require(o.minJudgment in setOf("success", "partial")) { "minJudgment must be success|partial" }
        val manifestPath = o.outDir.resolve("manifest.json")
        check(o.force || !Files.exists(manifestPath)) { "Output exists; use --force to overwrite" }
        Files.createDirectories(o.outDir)

        val redactor = if (o.redact) Redactor(userPatternsFile) else null
        val redact: (String) -> String = { s -> redactor?.redact(s) ?: s }
        val sft = SftBuilder(redact)
        val dpo = DpoBuilder(redact)

        fun rank(j: String?) = when (j) { "success" -> 2; "partial" -> 1; else -> 0 }
        val outcomes = inputs.judgedOutcomes().values
            .filter { o.scope == null || it.scope == o.scope }
            .filter { o.since == null || it.ts >= o.since }
        val negatives = inputs.negativeSessions()
        val subagentIds = inputs.subagentSessionIds()

        var legacy = 0; var dups = 0
        val seen = mutableSetOf<String>()
        val sftLines = mutableListOf<Pair<String, String>>()   // sessionId to line
        val eligible = outcomes.filter { out ->
            rank(out.judgment) >= rank(o.minJudgment) &&
                out.sessionId !in negatives && out.sessionId !in subagentIds
        }
        eligible.forEach { out ->
            val session = inputs.loadSession(out.sessionId) ?: return@forEach
            val (model, systemPrompt) = inputs.configSnapshot(out.sessionId)
            // A session whose entries fail to build (e.g. malformed toolCalls
            // metadata) is skipped entirely, exactly like a failed loadSession.
            val lines = runCatching {
                if (o.perTurn) sft.buildPerTurn(session.entries, systemPrompt)
                else listOf(sft.build(session.entries, systemPrompt))
            }.getOrNull() ?: return@forEach
            val hasToolRounds = session.entries.any { it.metadata.containsKey("toolCalls") }
            if (!hasToolRounds && model == null) legacy++
            lines.forEach { line ->
                if (seen.add(sha256(line))) sftLines.add(out.sessionId to line) else dups++
            }
        }

        var unpairable = 0
        val dpoLines = inputs.dpoLinks()
            .filter { (neg, _) -> o.scope == null || neg.scope == o.scope }
            .filter { (neg, _) -> o.since == null || neg.ts >= o.since }
            .mapNotNull { (neg, pos) ->
                val session = inputs.loadSession(neg.sessionId) ?: return@mapNotNull null
                val (_, systemPrompt) = inputs.configSnapshot(neg.sessionId)
                // A build that throws (e.g. malformed toolCalls metadata) is an
                // unusable link, same as a build that returns null.
                runCatching { dpo.build(session.entries, systemPrompt, neg.entryIndex, pos.entryIndex) }
                    .getOrNull()
                    ?.let { neg.sessionId to it }
                    ?: run { unpairable++; null }
            }
        val unpairedNegatives = inputs.activePreferences()
            .filter { o.scope == null || it.scope == o.scope }
            .filter { o.since == null || it.ts >= o.since }
            .count { it.polarity == "negative" && it.pairedWith == null }

        writeSplit(o, o.outDir, "sft", sftLines)
        writeSplit(o, o.outDir, "dpo", dpoLines)

        val manifest = buildJsonObject {
            put("createdAt", Instant.now().toString())
            put("filters", buildJsonObject {
                put("scope", o.scope ?: "")
                put("since", o.since ?: 0L)
                put("minJudgment", o.minJudgment)
            })
            put("counts", buildJsonObject {
                put("sftExamples", sftLines.size); put("dpoPairs", dpoLines.size)
                put("sessionsScanned", outcomes.size); put("sessionsEligible", eligible.size)
                put("legacySessions", legacy); put("duplicatesDropped", dups)
                put("unpairedNegatives", unpairedNegatives); put("unpairableLinks", unpairable)
            })
            put("redaction", buildJsonObject {
                put("enabled", o.redact)
                put("hitsByType", buildJsonObject {
                    (redactor?.hitsByType ?: emptyMap()).forEach { (k, v) -> put(k, v) }
                })
            })
            put("sourceSessionIds", buildJsonArray {
                (sftLines.map { it.first } + dpoLines.map { it.first }).distinct().forEach { add(it) }
            })
            put("split", o.split ?: 0.0)
            put("toolVersion", "1.0.0-SNAPSHOT")
        }
        Files.writeString(manifestPath, manifest.toString())
        return ExportResult(sftLines.size, dpoLines.size, manifestPath)
    }

    private fun writeSplit(o: ExportOptions, dir: Path, base: String, lines: List<Pair<String, String>>) {
        if (o.split == null) {
            Files.writeString(dir.resolve("$base.jsonl"), lines.joinToString("\n") { it.second })
            return
        }
        val (train, eval) = lines.partition { (sid, _) ->
            Math.floorMod(sid.hashCode(), 100) < (o.split * 100).toInt()
        }
        Files.writeString(dir.resolve("$base.jsonl"), train.joinToString("\n") { it.second })
        Files.writeString(dir.resolve("$base.eval.jsonl"), eval.joinToString("\n") { it.second })
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
