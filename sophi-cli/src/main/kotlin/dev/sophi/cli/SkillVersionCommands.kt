package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillVersionStore
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.writeText

private val globalSkillsDirForVersions: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")

private fun store(home: Path) = SkillVersionStore(home.resolve(".versions.jsonl"))

class SkillVersionsList(
    private val home: Path,
    private val skillId: String,
    private val project: Boolean,
    private val echo: (String) -> Unit
) {
    fun run() {
        val versions = store(home).history(skillId, project)
        if (versions.isEmpty()) { echo("No versions recorded for $skillId."); return }
        versions.forEach { echo("${it.id}  ${Instant.ofEpochMilli(it.ts)}${if (it.trial) "  [trial]" else ""}") }
    }
}

class SkillVersionsRevert(
    private val home: Path,
    private val skillId: String,
    private val versionId: String,
    private val project: Boolean,
    private val echo: (String) -> Unit
) {
    fun run() {
        val target = store(home).get(versionId)
        if (target == null || target.skillId != skillId) {
            echo("No version found with id $versionId for skill $skillId")
            return
        }

        val resolved = home.resolve("$skillId.md")
        resolved.writeText(target.content)

        val reread = runCatching { SkillLoader().loadFile(resolved) }.getOrNull()
        if (reread == null) {
            echo("Error: reverted $skillId.md but it failed to re-parse")
            return
        }

        store(home).record(target.copy(id = "skillver_" + UUID.randomUUID(), ts = System.currentTimeMillis()))
        echo("Reverted $skillId to version $versionId")
    }
}

class SkillVersionsCommand : CliktCommand(name = "versions", help = "List recorded versions of a skill") {
    private val id by argument()
    private val project by option("--project", help = "Look under ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val home = if (project) Path.of(".sophi", "skills") else globalSkillsDirForVersions
        SkillVersionsList(home, id, project) { echo(it) }.run()
    }
}

class SkillRevertCommand : CliktCommand(name = "revert", help = "Revert a skill to a previously recorded version") {
    private val id by argument()
    private val versionId by argument(name = "version-id")
    private val project by option("--project", help = "Look under ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val home = if (project) Path.of(".sophi", "skills") else globalSkillsDirForVersions
        SkillVersionsRevert(home, id, versionId, project) { echo(it) }.run()
    }
}
