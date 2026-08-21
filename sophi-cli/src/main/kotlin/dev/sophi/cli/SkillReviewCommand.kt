package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.ToolEvent
import dev.sophi.sdk.computeSkillAttribution
import dev.sophi.sdk.computeUnattributedInvocationCounts
import dev.sophi.skills.SkillInvocationStore
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

private val globalSkillsHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")
private val projectSkillsHome: Path = Path.of(".sophi", "skills")
private val globalLearningHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning")

@Serializable
private data class UnattributedCount(val skillId: String, val count: Int)

private fun allToolEvents(learningHome: Path): List<ToolEvent> {
    val json = Json { ignoreUnknownKeys = true }
    return JsonlLog(learningHome.resolve("tool-events.jsonl")).readAll()
        .mapNotNull { runCatching { json.decodeFromString<ToolEvent>(it) }.getOrNull() }
}

/**
 * Unions global and project-scoped versions of every skill id; a skill id with any project-scoped
 * versions uses only those for attribution, mirroring SkillRegistry.load's own "project wins on
 * id collision" resolution rule rather than inventing a new one.
 */
private fun resolvedVersions(skillsHome: Path, projectSkillsHome: Path): List<SkillVersion> {
    val global = SkillVersionStore(skillsHome.resolve(".versions.jsonl")).all()
    val project = SkillVersionStore(projectSkillsHome.resolve(".versions.jsonl")).all()
    return (global + project).groupBy { it.skillId }.flatMap { (_, versions) ->
        val projectOnly = versions.filter { it.project }
        if (projectOnly.isNotEmpty()) projectOnly else versions
    }
}

class SkillReview(
    private val skillsHome: Path,
    private val projectSkillsHome: Path,
    private val learningHome: Path,
    private val filterId: String?,
    private val echo: (String) -> Unit
) {
    fun run() {
        val versions = resolvedVersions(skillsHome, projectSkillsHome)
        val invocations = SkillInvocationStore(skillsHome.resolve(".invocations.jsonl")).all()
        val toolEvents = allToolEvents(learningHome)
        val attribution = computeSkillAttribution(versions, invocations, toolEvents)
        val unattributed = computeUnattributedInvocationCounts(versions, invocations)

        val json = Json { encodeDefaults = true }
        Files.createDirectories(skillsHome)
        val attributionSnapshot = attribution.joinToString("") { json.encodeToString(it) + "\n" }
        Files.write(skillsHome.resolve(".attribution.jsonl"), attributionSnapshot.toByteArray())
        val unattributedSnapshot = unattributed.entries.joinToString("") {
            json.encodeToString(UnattributedCount(it.key, it.value)) + "\n"
        }
        Files.write(skillsHome.resolve(".unattributed.jsonl"), unattributedSnapshot.toByteArray())

        val toPrint = if (filterId == null) attribution else attribution.filter { it.skillId == filterId }
        if (toPrint.isEmpty()) { echo("No versions found${filterId?.let { " for $it" } ?: ""}."); return }
        toPrint.forEach {
            echo(
                "${it.skillId}  ${it.versionId}  project=${it.project}  trial=${it.trial}  " +
                    "invocations=${it.invocationCount}  adjacentFailures=${it.adjacentFailures}"
            )
        }
        val totalUnattributed = unattributed.values.sum()
        if (totalUnattributed > 0) {
            echo("$totalUnattributed invocation(s) across ${unattributed.size} skill(s) could not be attributed to any recorded version — see .unattributed.jsonl")
        }
    }
}

class SkillReviewCommand : CliktCommand(name = "review", help = "Report mechanical attribution evidence for self-authored skills") {
    private val id by argument().optional()

    override fun run() = SkillReview(globalSkillsHome, projectSkillsHome, globalLearningHome, id) { echo(it) }.run()
}
