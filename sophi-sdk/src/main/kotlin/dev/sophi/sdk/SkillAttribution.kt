package dev.sophi.sdk

import dev.sophi.learning.ToolEvent
import dev.sophi.skills.SkillInvocationEvent
import dev.sophi.skills.SkillVersion
import kotlinx.serialization.Serializable

@Serializable
data class SkillVersionAttribution(
    val skillId: String,
    val versionId: String,
    val versionTs: Long,
    val project: Boolean,
    val trial: Boolean,
    val invocationCount: Int,
    val adjacentFailures: Int
)

/**
 * Mechanical, no-LLM correlation: for each recorded skill version, buckets the invocations that
 * happened while that version was the closest-preceding one written, then counts how many were
 * immediately followed (in the same session, excluding the read's own logged ToolEvent) by a
 * failing ToolEvent versus a clean one.
 */
fun computeSkillAttribution(
    versions: List<SkillVersion>,
    invocations: List<SkillInvocationEvent>,
    toolEvents: List<ToolEvent>
): List<SkillVersionAttribution> {
    val eventsBySession = toolEvents.groupBy { it.sessionId }.mapValues { it.value.sortedBy { e -> e.ts } }
    val bySkill = versions.groupBy { it.skillId }
    return versions.map { version ->
        val ordered = bySkill.getValue(version.skillId)
            .withIndex().sortedWith(compareBy({ it.value.ts }, { it.index })).map { it.value }
        val versionIndex = ordered.indexOfFirst { it.id == version.id }
        val forThisVersion = invocations.filter { inv ->
            inv.skillId == version.skillId && ordered.indexOfLast { it.ts <= inv.ts } == versionIndex
        }
        val adjacentFailures = forThisVersion.count { inv ->
            val sessionEvents = eventsBySession[inv.sessionId].orEmpty()
            sessionEvents.firstOrNull { it.ts > inv.ts && it.tool != "skill" }?.success == false
        }
        SkillVersionAttribution(
            version.skillId, version.id, version.ts, version.project, version.trial,
            forThisVersion.size, adjacentFailures
        )
    }
}

/**
 * Invocations computeSkillAttribution can't attribute to any version: a skill id with no recorded
 * version at all (installed, hand-authored, or read before versioning shipped), or an invocation
 * that predates that skill's earliest recorded version.
 */
fun computeUnattributedInvocationCounts(
    versions: List<SkillVersion>,
    invocations: List<SkillInvocationEvent>
): Map<String, Int> {
    val earliestBySkill = versions.groupBy { it.skillId }.mapValues { it.value.minOf { v -> v.ts } }
    return invocations
        .filter { inv -> (earliestBySkill[inv.skillId] ?: Long.MAX_VALUE) > inv.ts }
        .groupingBy { it.skillId }
        .eachCount()
}
