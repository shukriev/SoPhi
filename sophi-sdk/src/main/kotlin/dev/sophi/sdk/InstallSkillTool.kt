package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.RuleVerdict
import dev.sophi.core.tools.Tool
import dev.sophi.skills.SkillInstaller
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

@Serializable
private data class InstallSkillArgs(
    val source: String,
    val only: List<String>? = null,
    val project: Boolean = false
)

class InstallSkillTool(
    private val installer: SkillInstaller = SkillInstaller(),
    private val resolveTargetDir: (project: Boolean) -> Path = { project ->
        if (project) Path.of(".sophi", "skills")
        else Path.of(System.getProperty("user.home"), ".sophi", "skills")
    }
) : Tool {
    override val name = "install_skill"
    override val description = "Install skills (Claude Code-shaped SKILL.md files) from a local " +
        "path or git URL into Sophi's skill directory, normalizing frontmatter automatically."
    override val parametersJson = """
        {"type":"object","properties":{
          "source":{"type":"string","description":"Local path or git URL to install skills from"},
          "only":{"type":"array","items":{"type":"string"},"description":"Skill ids to install; omit to install everything found"},
          "project":{"type":"boolean","description":"Install into ./.sophi/skills instead of the global ~/.sophi/skills"}
        },"required":["source"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun ruleVerdict(argumentsJson: String) = RuleVerdict.HIGH_RISK

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString(InstallSkillArgs.serializer(), argumentsJson) }
            .getOrNull() ?: return "Error: invalid arguments"
        val targetDir = resolveTargetDir(args.project)
        val result = runCatching {
            installer.install(args.source, targetDir, args.only?.toSet() ?: emptySet()) { _, content ->
                checkInstalledSkillContent(content)
            }
        }.getOrElse { return "Error: ${it.message}" }

        result.installed.forEach { id ->
            val versionStore = SkillVersionStore(
                VersionStore(targetDir.resolve(".versions")), args.project,
                legacyJsonlPath = targetDir.resolve(".versions.jsonl")
            )
            versionStore.record(SkillVersion(skillId = id, project = args.project, content = targetDir.resolve("$id.md").readText(), trial = true))
        }

        return buildString {
            if (result.installed.isNotEmpty()) appendLine("Installed: ${result.installed.joinToString(", ")}")
            if (result.skipped.isNotEmpty()) appendLine("Already installed: ${result.skipped.joinToString(", ")}")
            if (result.notFound.isNotEmpty()) appendLine("Not found: ${result.notFound.joinToString(", ")}")
            if (result.rejected.isNotEmpty()) {
                appendLine("Rejected: " + result.rejected.entries.joinToString("; ") { (id, reasons) -> "$id (${reasons.joinToString(", ")})" })
            }
            if (isEmpty()) append("No skills found under ${args.source}.")
        }.trim()
    }

    override fun confirmationPreview(argumentsJson: String): String? {
        val args = runCatching { json.decodeFromString(InstallSkillArgs.serializer(), argumentsJson) }.getOrNull() ?: return null
        val targetDesc = if (args.project) "./.sophi/skills" else "~/.sophi/skills"
        val onlyDesc = args.only?.let { " (only: ${it.joinToString(", ")})" } ?: " (all discovered skills)"
        return "Install from ${args.source}$onlyDesc into $targetDesc. Content is fetched and scanned only " +
            "after approval — exact skill ids aren't known until the source is cloned."
    }
}
