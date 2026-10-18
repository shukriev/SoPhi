package dev.sophi.versioning

import dev.sophi.store.arcade.ArcadeStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Embedded homes open around every operation (ADR-029); the default homes are one store on the shared server
 * (ADR-041 M6, [VersioningStores]).
 */
class VersionStore(private val db: VersioningDb) : AutoCloseable {
    constructor(home: Path) : this(VersioningDb.embedded(home))

    private fun <T> withStore(block: (ArcadeStore) -> T): T = db.use { store ->
        store.ensureSchema(vertexTypes = listOf("Version"), edgeTypes = listOf("MUTATED_FROM"))
        block(store)
    }

    fun record(
        artifactType: ArtifactType,
        artifactId: String,
        content: String,
        producedBy: ProducedBy,
        note: String? = null
    ): Version = withStore { store ->
        val parent = allForArtifact(store, artifactType, artifactId).maxByOrNull { it.createdAtMs }
        val version = Version(
            id = "ver_" + UUID.randomUUID(), artifactType = artifactType, artifactId = artifactId,
            contentHash = sha256(content), content = content, parentVersionId = parent?.id,
            createdAtMs = System.currentTimeMillis(), producedBy = producedBy, note = note
        )
        store.upsertVertex("Version", version.id, toProperties(version))
        parent?.let { store.upsertEdge("MUTATED_FROM", "Version", version.id, it.id, emptyMap()) }
        version
    }

    fun history(artifactType: ArtifactType, artifactId: String): List<Version> =
        withStore { store -> allForArtifact(store, artifactType, artifactId).sortedBy { it.createdAtMs } }

    /**
     * The newest version an unattended tournament should treat as "current" for [artifactId] —
     * the newest [ProducedBy.HUMAN] record, not simply the newest record. A [ProducedBy.TOURNAMENT]
     * entry is a proposal `history()` still returns (append-only, regardless of accept/reject);
     * treating it as the baseline would let a rejected challenger silently become the next run's
     * incumbent.
     */
    fun activeVersion(artifactType: ArtifactType, artifactId: String): Version? =
        history(artifactType, artifactId).lastOrNull { it.producedBy == ProducedBy.HUMAN }

    fun allForType(artifactType: ArtifactType): List<Version> =
        withStore { store -> store.queryVertices("Version").map { fromProperties(it) }.filter { it.artifactType == artifactType } }

    fun get(id: String): Version? = withStore { store -> store.getVertex("Version", id)?.let { fromProperties(it) } }

    override fun close() = db.close()

    fun revert(artifactType: ArtifactType, artifactId: String, versionId: String): Version {
        val target = get(versionId) ?: error("no such version: $versionId")
        return record(artifactType, artifactId, target.content, target.producedBy, note = "revert to $versionId")
    }

    // queryVertices(type) returns every vertex of that type with no server-side filter, so
    // artifactType/artifactId scoping happens client-side — acceptable at this phase's scale
    // (dozens to low hundreds of versions), not millions.
    private fun allForArtifact(store: ArcadeStore, artifactType: ArtifactType, artifactId: String): List<Version> =
        store.queryVertices("Version")
            .map { fromProperties(it) }
            .filter { it.artifactType == artifactType && it.artifactId == artifactId }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun toProperties(v: Version): Map<String, Any?> = mapOf(
        "artifactType" to v.artifactType.name,
        "artifactId" to v.artifactId,
        "contentHash" to v.contentHash,
        "content" to v.content,
        "parentVersionId" to v.parentVersionId,
        "createdAtMs" to v.createdAtMs,
        "producedBy" to v.producedBy.name,
        "note" to v.note
    )

    private fun fromProperties(p: Map<String, Any?>): Version = Version(
        id = p["id"] as String,
        artifactType = ArtifactType.valueOf(p["artifactType"] as String),
        artifactId = p["artifactId"] as String,
        contentHash = p["contentHash"] as String,
        content = p["content"] as String,
        parentVersionId = p["parentVersionId"] as String?,
        createdAtMs = (p["createdAtMs"] as Number).toLong(),
        producedBy = ProducedBy.valueOf(p["producedBy"] as String),
        note = p["note"] as String?
    )
}
