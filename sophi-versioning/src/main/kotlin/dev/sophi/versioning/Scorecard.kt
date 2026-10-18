package dev.sophi.versioning

import dev.sophi.store.arcade.ArcadeStore
import java.nio.file.Path
import java.util.UUID

data class Scorecard(
    val id: String,
    val configVersionId: String,
    val headlineScore: Double,
    val perCategory: Map<String, Double>,
    val quarantinedCaseIds: List<String>,
    val totalCases: Int,
    val createdAtMs: Long
)

/**
 * Separate from [VersionStore]: a scorecard is a measurement result, not a versioned artifact with
 * lineage/revert semantics — it doesn't fit [ArtifactType]/[ProducedBy]. Shares the same
 * open-write-close ArcadeDB access pattern.
 */
class ScorecardStore(private val db: VersioningDb) : AutoCloseable {
    constructor(home: Path) : this(VersioningDb.embedded(home))

    private fun <T> withStore(block: (ArcadeStore) -> T): T = db.use { store ->
        store.ensureSchema(vertexTypes = listOf("Scorecard"))
        block(store)
    }

    fun record(
        configVersionId: String,
        headlineScore: Double,
        perCategory: Map<String, Double>,
        quarantinedCaseIds: List<String>,
        totalCases: Int
    ): Scorecard = withStore { store ->
        val scorecard = Scorecard(
            id = "score_" + UUID.randomUUID(), configVersionId = configVersionId,
            headlineScore = headlineScore, perCategory = perCategory,
            quarantinedCaseIds = quarantinedCaseIds, totalCases = totalCases,
            createdAtMs = System.currentTimeMillis()
        )
        store.upsertVertex("Scorecard", scorecard.id, toProperties(scorecard))
        scorecard
    }

    fun forConfigVersion(configVersionId: String): List<Scorecard> = withStore { store ->
        store.queryVertices("Scorecard").map { fromProperties(it) }.filter { it.configVersionId == configVersionId }
    }

    override fun close() = db.close()

    private fun toProperties(s: Scorecard): Map<String, Any?> = mapOf(
        "configVersionId" to s.configVersionId,
        "headlineScore" to s.headlineScore,
        "perCategory" to s.perCategory.entries.joinToString(";") { "${it.key}:${it.value}" },
        "quarantinedCaseIds" to s.quarantinedCaseIds.joinToString(","),
        "totalCases" to s.totalCases,
        "createdAtMs" to s.createdAtMs
    )

    private fun fromProperties(p: Map<String, Any?>): Scorecard = Scorecard(
        id = p["id"] as String,
        configVersionId = p["configVersionId"] as String,
        headlineScore = (p["headlineScore"] as Number).toDouble(),
        perCategory = (p["perCategory"] as String).takeIf { it.isNotEmpty() }
            ?.split(";")?.associate { entry -> entry.substringBefore(":") to entry.substringAfter(":").toDouble() }
            ?: emptyMap(),
        quarantinedCaseIds = (p["quarantinedCaseIds"] as String).takeIf { it.isNotEmpty() }?.split(",") ?: emptyList(),
        totalCases = (p["totalCases"] as Number).toInt(),
        createdAtMs = (p["createdAtMs"] as Number).toLong()
    )
}
