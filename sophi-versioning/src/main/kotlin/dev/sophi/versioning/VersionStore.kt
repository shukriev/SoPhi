package dev.sophi.versioning

import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Every operation opens its ArcadeDB instance, does its work, and closes it immediately — never
 * held open across calls or for a process's lifetime (unlike JanesPalace's open-at-construction
 * pattern). CLI, companion, and the scheduler daemon are all separate processes that could each
 * want this database open at once; short-lived opens let them serialize instead of permanently
 * locking each other out.
 */
class VersionStore(private val home: Path) {
    fun record(
        artifactType: ArtifactType,
        artifactId: String,
        content: String,
        producedBy: ProducedBy,
        note: String? = null
    ): Version {
        val store = EmbeddedArcadeStore.open(home)
        try {
            store.ensureSchema(vertexTypes = listOf("Version"), edgeTypes = listOf("MUTATED_FROM"))
            val parent = allForArtifact(store, artifactType, artifactId).maxByOrNull { it.createdAtMs }
            val version = Version(
                id = "ver_" + UUID.randomUUID(),
                artifactType = artifactType,
                artifactId = artifactId,
                contentHash = sha256(content),
                content = content,
                parentVersionId = parent?.id,
                createdAtMs = System.currentTimeMillis(),
                producedBy = producedBy,
                note = note
            )
            store.upsertVertex("Version", version.id, toProperties(version))
            parent?.let { store.upsertEdge("MUTATED_FROM", "Version", version.id, it.id, emptyMap()) }
            return version
        } finally {
            store.close()
        }
    }

    fun history(artifactType: ArtifactType, artifactId: String): List<Version> {
        val store = EmbeddedArcadeStore.open(home)
        try {
            store.ensureSchema(vertexTypes = listOf("Version"), edgeTypes = listOf("MUTATED_FROM"))
            return allForArtifact(store, artifactType, artifactId).sortedBy { it.createdAtMs }
        } finally {
            store.close()
        }
    }

    fun get(id: String): Version? {
        val store = EmbeddedArcadeStore.open(home)
        try {
            store.ensureSchema(vertexTypes = listOf("Version"), edgeTypes = listOf("MUTATED_FROM"))
            return store.getVertex("Version", id)?.let { fromProperties(it) }
        } finally {
            store.close()
        }
    }

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
