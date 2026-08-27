package dev.sophi.versioning

enum class ArtifactType { SKILL, LESSON, CONFIG, AGENT_DEFINITION, MEMORY_CONSOLIDATION }

enum class ProducedBy { HUMAN, WRITE_SKILL_TOOL, REFLECTION, TOURNAMENT, MIGRATION }

data class Version(
    val id: String,
    val artifactType: ArtifactType,
    val artifactId: String,
    val contentHash: String,
    val content: String,
    val parentVersionId: String?,
    val createdAtMs: Long,
    val producedBy: ProducedBy,
    val note: String? = null
)
