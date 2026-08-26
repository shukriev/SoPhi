package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.VersionStore
import java.nio.file.Path
import java.time.Instant

private fun versionStoreHomeFor(type: ArtifactType, project: Boolean): Path = when (type) {
    ArtifactType.SKILL ->
        if (project) Path.of(".sophi", "skills", ".versions")
        else Path.of(System.getProperty("user.home"), ".sophi", "skills", ".versions")
    ArtifactType.LESSON -> Path.of(System.getProperty("user.home"), ".sophi", "learning", ".versions")
    ArtifactType.CONFIG -> Path.of(System.getProperty("user.home"), ".sophi", "versioning")
    ArtifactType.AGENT_DEFINITION -> Path.of(System.getProperty("user.home"), ".sophi", "agents", ".versions")
}

private fun parseType(raw: String): ArtifactType =
    ArtifactType.entries.find { it.name.equals(raw, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown artifact type '$raw' — expected one of ${ArtifactType.entries.joinToString(", ")}")

class VersionsList(
    private val store: VersionStore,
    private val artifactType: ArtifactType,
    private val artifactId: String,
    private val echo: (String) -> Unit
) {
    fun run() {
        val versions = store.history(artifactType, artifactId)
        if (versions.isEmpty()) { echo("No versions recorded for $artifactId."); return }
        versions.asReversed().forEach { echo("${it.id}  ${Instant.ofEpochMilli(it.createdAtMs)}  producedBy=${it.producedBy}") }
    }
}

class VersionsShow(
    private val store: VersionStore,
    private val versionId: String,
    private val echo: (String) -> Unit
) {
    fun run() {
        val version = store.get(versionId)
        if (version == null) {
            echo("No version found with id $versionId")
            return
        }
        echo("id=${version.id}  artifactId=${version.artifactId}  producedBy=${version.producedBy}  createdAt=${Instant.ofEpochMilli(version.createdAtMs)}")
        version.note?.let { echo("note: $it") }
        echo("---")
        echo(version.content)
    }
}

class VersionsRevert(
    private val store: VersionStore,
    private val artifactType: ArtifactType,
    private val artifactId: String,
    private val versionId: String,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (store.get(versionId) == null) {
            echo("No version found with id $versionId for $artifactId")
            return
        }
        val reverted = store.revert(artifactType, artifactId, versionId)
        echo("$artifactId reverted to version $versionId (new version: ${reverted.id})")
    }
}

class VersionsListCommand : CliktCommand(name = "list", help = "List recorded versions of an artifact") {
    private val type by argument()
    private val id by argument()
    private val project by option("--project", help = "For type=SKILL: look under ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val artifactType = parseType(type)
        VersionsList(VersionStore(versionStoreHomeFor(artifactType, project)), artifactType, id) { echo(it) }.run()
    }
}

class VersionsShowCommand : CliktCommand(name = "show", help = "Show a specific version's content and metadata") {
    private val type by argument()
    private val id by argument()
    private val versionId by argument(name = "version-id")
    private val project by option("--project", help = "For type=SKILL: look under ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val artifactType = parseType(type)
        VersionsShow(VersionStore(versionStoreHomeFor(artifactType, project)), versionId) { echo(it) }.run()
    }
}

class VersionsRevertCommand : CliktCommand(name = "revert", help = "Revert an artifact to a previously recorded version") {
    private val type by argument()
    private val id by argument()
    private val versionId by argument(name = "version-id")
    private val project by option("--project", help = "For type=SKILL: look under ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val artifactType = parseType(type)
        VersionsRevert(VersionStore(versionStoreHomeFor(artifactType, project)), artifactType, id, versionId) { echo(it) }.run()
    }
}

class VersionsCommand : CliktCommand(name = "versions", help = "List, show, and revert versioned artifacts (skills, lessons, configs, agent definitions)") {
    override fun run() = Unit
}
