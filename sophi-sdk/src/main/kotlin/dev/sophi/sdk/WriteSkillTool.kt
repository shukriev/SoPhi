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

private val SITE_ID_PATTERN = Regex("^site-[a-z0-9-]{1,60}(/[a-z0-9-]{1,60})?$")

@Serializable
private data class WriteSkillArgs(
    val id: String,
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val body: String,
    val project: Boolean = false,
    val domain: Boolean = false
)

/**
 * The only way for the model to write a skill outside the CWD sandbox FileWriteTool enforces —
 * see the design spec's "Core finding" section. Namespaced to `site-*` so it can never clobber
 * a hand-authored or meta-skill file: there's no separate `id` field in SkillMetadata, so the
 * filename IS the id, and an unrestricted write would be an arbitrary global-skill overwrite.
 *
 * `id` may contain at most one `/`, addressing a domain member (`site-<domain>/<member>`) — see
 * docs/superpowers/specs/2026-08-31-skill-domain-grouping-design.md. Each segment stays
 * restricted to [a-z0-9-], so `..` and absolute paths remain structurally unreachable.
 * [resolveWritePath] is the single place both execute() and confirmationPreview() compute a
 * target path from an id, so the two can never diverge.
 */
class WriteSkillTool(
    private val resolveTargetDir: (project: Boolean) -> Path = { project ->
        if (project) Path.of(".sophi", "skills")
        else Path.of(System.getProperty("user.home"), ".sophi", "skills")
    }
) : Tool {
    override val name = "write_skill"
    override val description = "Create or update a site-specific skill (id must match " +
        "site-<slug>, or site-<slug>/<member> for a domain member — e.g. site-github-com or " +
        "site-maidplus-de/companies) documenting a website's workflows so Sophi can recall it " +
        "on a future visit instead of re-exploring. Set domain=true to create/update the shared " +
        "index for a group of related skills (id must have no '/' segment in that case). Never " +
        "include credentials, tokens, or secrets in the body."
    override val parametersJson = """
        {"type":"object","properties":{
          "id":{"type":"string","description":"site-<slug> derived from the hostname, e.g. site-github-com, or site-<slug>/<member> for a domain member, e.g. site-maidplus-de/companies"},
          "title":{"type":"string"},
          "description":{"type":"string"},
          "tags":{"type":"array","items":{"type":"string"}},
          "body":{"type":"string","description":"Markdown body: entry URL(s), workflow steps, selectors/anchors that worked, gotchas"},
          "project":{"type":"boolean","description":"Write into ./.sophi/skills instead of the global ~/.sophi/skills"},
          "domain":{"type":"boolean","description":"True to create/update the shared index for a domain (id must have no '/' segment). False (default) for a standalone or member skill."}
        },"required":["id","title","description","body"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.decodeFromString(WriteSkillArgs.serializer(), argumentsJson) }
            .getOrNull() ?: return "Error: invalid arguments"

        validate(args)?.let { return it }

        val content = renderSkillContent(args)
        val violations = checkSkillContent(content)
        if (violations.isNotEmpty()) return "Error: rejected — ${violations.joinToString("; ")}"

        val targetDir = resolveTargetDir(args.project)
        targetDir.createDirectories()
        val resolved = resolveWritePath(targetDir, args)
        resolved.parent.createDirectories()

        val versionStore = SkillVersionStore(
            VersionStore(targetDir.resolve(".versions")), args.project,
            legacyJsonlPath = targetDir.resolve(".versions.jsonl")
        )

        // A file that predates this tool, or was hand-edited outside it, has no baseline version —
        // snapshot its current on-disk content as the root version before this write overwrites it.
        if (resolved.exists() && versionStore.history(args.id, args.project).isEmpty()) {
            versionStore.record(SkillVersion(skillId = args.id, project = args.project, content = resolved.readText(), trial = false))
        }

        resolved.writeText(content)

        val reread = runCatching { SkillLoader().loadFile(resolved) }.getOrNull()
        if (reread == null || reread.metadata.title != args.title) {
            return "Error: wrote ${args.id} but it failed to re-parse — check the title/description for characters kaml can't round-trip"
        }

        versionStore.record(SkillVersion(skillId = args.id, project = args.project, content = content, trial = true))

        return "Wrote skill '${args.id}' to $resolved"
    }

    override fun confirmationPreview(argumentsJson: String): String? {
        val args = runCatching { json.decodeFromString(WriteSkillArgs.serializer(), argumentsJson) }.getOrNull() ?: return null
        if (validate(args) != null) return null // invalid — fall back to raw JSON display, don't guess a path
        val targetDir = resolveTargetDir(args.project)
        val existingPath = resolveWritePath(targetDir, args)
        val newContent = renderSkillContent(args)
        val header = "Write skill '${args.id}' (title: ${args.title})"
        return if (!existingPath.exists()) "$header\n(new skill)\n$newContent"
        else "$header\n${lineDiff(existingPath.readText(), newContent)}"
    }

    /** Null when [args] is valid; an error string otherwise. Shared by execute() and
     *  confirmationPreview() so they can never disagree about what's acceptable. */
    private fun validate(args: WriteSkillArgs): String? {
        if (!SITE_ID_PATTERN.matches(args.id)) {
            return "Error: id must match ${SITE_ID_PATTERN.pattern} (got: ${args.id})"
        }
        val isMember = '/' in args.id
        if (args.domain && isMember) {
            return "Error: domain=true is only valid for a domain-root id (no '/'); '${args.id}' is a member id"
        }
        if (args.domain) {
            val flatPath = resolveTargetDir(args.project).resolve("${args.id}.md")
            if (flatPath.exists()) {
                return "Error: a flat skill already exists at '${args.id}.md' — pick a different domain id, it can't share a name with an existing skill"
            }
        }
        return null
    }

    /** The single place a target path is computed from [args] — used by both execute() and
     *  confirmationPreview(), so they can never diverge. */
    private fun resolveWritePath(targetDir: Path, args: WriteSkillArgs): Path = when {
        '/' in args.id -> targetDir.resolve("${args.id}.md")
        args.domain -> targetDir.resolve(args.id).resolve("_index.md")
        else -> targetDir.resolve("${args.id}.md")
    }

    private fun renderSkillContent(args: WriteSkillArgs): String {
        val frontmatter = Yaml.default.encodeToString(
            SkillMetadata.serializer(),
            SkillMetadata(title = args.title, description = args.description, tags = args.tags)
        )
        return "---\n$frontmatter\n---\n${args.body}"
    }
}

// ponytail: line-set diff, not aligned/ordered — good enough for a confirmation preview.
private fun lineDiff(old: String, new: String): String {
    val oldLines = old.lines().toSet()
    val newLines = new.lines().toSet()
    val added = new.lines().filter { it !in oldLines }
    val removed = old.lines().filter { it !in newLines }
    if (added.isEmpty() && removed.isEmpty()) return "(no textual change)"
    return buildString {
        removed.forEach { appendLine("- $it") }
        added.forEach { appendLine("+ $it") }
    }.trim()
}
