package dev.sophi.skills

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.Version
import dev.sophi.versioning.VersionStore
import java.nio.file.Path

/**
 * [project] is fixed at construction — each instance represents exactly one scope (global or
 * project-local skills), the same separation the pre-migration JSONL-per-directory design had.
 * [Version] carries no project field of its own, so this class supplies it when reconstructing a
 * [SkillVersion] from a generic [Version].
 *
 * [legacyJsonlPath], if given, is migrated into [versionStore] (once, idempotently) before every
 * read — transparent migration on first use, with no separate migration step for a caller to
 * remember to run.
 */
class SkillVersionStore(
    private val versionStore: VersionStore,
    private val project: Boolean,
    private val legacyJsonlPath: Path? = null
) {
    fun record(version: SkillVersion): SkillVersion {
        migrateIfNeeded()
        val recorded = versionStore.record(
            ArtifactType.SKILL, version.skillId, version.content,
            if (version.trial) ProducedBy.WRITE_SKILL_TOOL else ProducedBy.HUMAN
        )
        return recorded.toSkillVersion(project)
    }

    fun history(skillId: String, project: Boolean): List<SkillVersion> {
        require(project == this.project) { "this store was constructed for project=${this.project}, not $project" }
        migrateIfNeeded()
        return versionStore.history(ArtifactType.SKILL, skillId).asReversed().map { it.toSkillVersion(project) }
    }

    fun get(id: String): SkillVersion? {
        migrateIfNeeded()
        return versionStore.get(id)?.takeIf { it.artifactType == ArtifactType.SKILL }?.toSkillVersion(project)
    }

    fun all(): List<SkillVersion> {
        migrateIfNeeded()
        return versionStore.allForType(ArtifactType.SKILL).map { it.toSkillVersion(project) }
    }

    private fun migrateIfNeeded() {
        legacyJsonlPath?.let { migrateSkillVersions(it, versionStore) }
    }

    private fun Version.toSkillVersion(project: Boolean) = SkillVersion(
        id = id, ts = createdAtMs, skillId = artifactId, project = project, content = content,
        trial = producedBy == ProducedBy.WRITE_SKILL_TOOL
    )
}
