package dev.sophi.sdk

import com.charleskorn.kaml.Yaml
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val SITE_ID_PATTERN = Regex("^site-[a-z0-9-]{1,60}$")

@Serializable
private data class WriteSkillArgs(
    val id: String,
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val body: String,
    val project: Boolean = false
)

/**
 * The only way for the model to write a skill outside the CWD sandbox FileWriteTool enforces —
 * see the design spec's "Core finding" section. Namespaced to `site-*` so it can never clobber
 * a hand-authored or meta-skill file: there's no separate `id` field in SkillMetadata, so the
 * filename IS the id, and an unrestricted write would be an arbitrary global-skill overwrite.
 */
class WriteSkillTool(
    private val resolveTargetDir: (project: Boolean) -> Path = { project ->
        if (project) Path.of(".sophi", "skills")
        else Path.of(System.getProperty("user.home"), ".sophi", "skills")
    }
) : Tool {
    override val name = "write_skill"
    override val description = "Create or update a site-specific skill (id must match " +
        "site-<slug>, e.g. site-github-com) documenting a website's workflows so Sophi can " +
        "recall it on a future visit instead of re-exploring. Never include credentials, " +
        "tokens, or secrets in the body."
    override val parametersJson = """
        {"type":"object","properties":{
          "id":{"type":"string","description":"site-<slug> derived from the hostname, e.g. site-github-com"},
          "title":{"type":"string"},
          "description":{"type":"string"},
          "tags":{"type":"array","items":{"type":"string"}},
          "body":{"type":"string","description":"Markdown body: entry URL(s), workflow steps, selectors/anchors that worked, gotchas"},
          "project":{"type":"boolean","description":"Write into ./.sophi/skills instead of the global ~/.sophi/skills"}
        },"required":["id","title","description","body"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString(WriteSkillArgs.serializer(), argumentsJson) }
            .getOrNull() ?: return "Error: invalid arguments"

        if (!SITE_ID_PATTERN.matches(args.id)) {
            return "Error: id must match ${SITE_ID_PATTERN.pattern} (got: ${args.id})"
        }

        val targetDir = resolveTargetDir(args.project)
        targetDir.createDirectories()
        // args.id is already constrained to [a-z0-9-] by SITE_ID_PATTERN above (no '/' or '.'
        // possible), so this can never resolve outside targetDir — no separate traversal check
        // needed, unlike FileWriteTool where the path argument itself is untrusted.
        val resolved = targetDir.resolve("${args.id}.md")
        val versionStore = SkillVersionStore(
            VersionStore(targetDir.resolve(".versions")), args.project,
            legacyJsonlPath = targetDir.resolve(".versions.jsonl")
        )

        // A file that predates this tool, or was hand-edited outside it, has no baseline version —
        // snapshot its current on-disk content as the root version before this write overwrites it.
        if (resolved.exists() && versionStore.history(args.id, args.project).isEmpty()) {
            versionStore.record(SkillVersion(skillId = args.id, project = args.project, content = resolved.readText(), trial = false))
        }

        val frontmatter = Yaml.default.encodeToString(
            SkillMetadata.serializer(),
            SkillMetadata(title = args.title, description = args.description, tags = args.tags)
        )
        val content = "---\n$frontmatter\n---\n${args.body}"
        resolved.writeText(content)

        val reread = runCatching { SkillLoader().loadFile(resolved) }.getOrNull()
        if (reread == null || reread.metadata.title != args.title) {
            return "Error: wrote ${args.id}.md but it failed to re-parse — check the title/description for characters kaml can't round-trip"
        }

        versionStore.record(SkillVersion(skillId = args.id, project = args.project, content = content, trial = true))

        return "Wrote skill '${args.id}' to $resolved"
    }
}
