package dev.sophi.skills

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

private val migrationJson = Json { ignoreUnknownKeys = true }

/**
 * One-time migration of a pre-Phase-1.5 `.versions.jsonl` file's entries into [versionStore],
 * grouped by skill id. A no-op for [oldJsonlPath] itself missing; per-skill-id idempotent (a
 * skill id that already has history in [versionStore] is skipped) — safe to call on every read,
 * including ones for a skill id not present in the legacy file at all.
 */
fun migrateSkillVersions(oldJsonlPath: Path, versionStore: VersionStore) {
    if (!oldJsonlPath.exists()) return
    val bySkillId = oldJsonlPath.readLines().filter { it.isNotBlank() }
        .mapNotNull { runCatching { migrationJson.decodeFromString<SkillVersion>(it) }.getOrNull() }
        .groupBy { it.skillId }
    for ((skillId, versions) in bySkillId) {
        if (versionStore.history(ArtifactType.SKILL, skillId).isNotEmpty()) continue
        versions.sortedBy { it.ts }.forEach { old ->
            versionStore.record(ArtifactType.SKILL, skillId, old.content, ProducedBy.MIGRATION, note = "migrated from ${old.id}")
        }
    }
}
