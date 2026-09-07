package dev.sophi.cli.selfmod

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

@Serializable
data class ShadowPrAuditEntry(
    val proposalId: String,
    val worktreePath: String,
    val branch: String,
    val decision: String,
    val reason: String,
    val prUrl: String? = null,
    val baselineScores: Map<String, List<Double>> = emptyMap(),
    val challengerScores: Map<String, List<Double>> = emptyMap(),
    val tsMs: Long = System.currentTimeMillis()
)

class ShadowPrAuditLog(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun record(entry: ShadowPrAuditEntry) {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(entry).replace("\n", " ")
        Files.write(path, (line + "\n").toByteArray(), CREATE, APPEND)
    }

    fun all(): List<ShadowPrAuditEntry> =
        if (!Files.exists(path)) emptyList()
        else Files.readAllLines(path).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<ShadowPrAuditEntry>(it) }.getOrNull() }
}
