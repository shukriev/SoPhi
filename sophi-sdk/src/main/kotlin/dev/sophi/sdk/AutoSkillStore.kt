package dev.sophi.sdk

import com.charleskorn.kaml.Yaml
import dev.sophi.skills.Skill
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillRegistry
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.VersionStore
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

const val AUTO_LEARNED_TAG = "auto-learned"
const val FROM_WEB_TAG = "from-web"
private const val AUTO_PREFIX = "auto-"
private val AUTO_ID = Regex("^auto-[a-z0-9-]{1,60}$")
private val PARAM_NAME = Regex("^[a-z0-9_]{1,40}$")
private const val MAX_TITLE = 80
private const val MAX_DESCRIPTION = 200

/**
 * ArcadeDB refuses a second open of the same database in one JVM, and every skill-version write
 * opens `.versions` briefly. Everything in this process that records skill versions holds this.
 */
internal object SkillVersionsLock

/** Auto-learned skills live only under `auto-*`, so they can never overwrite a `site-*` skill;
 *  [AutoSkillStore] also refuses `auto-*` files it didn't write. */
fun isAutoSkillId(id: String): Boolean = AUTO_ID.matches(id)

/** "Archive Trello emails!" → `auto-archive-trello-emails`; null when nothing usable is left. */
fun autoSkillId(raw: String): String? {
    val slug = raw.lowercase().removePrefix(AUTO_PREFIX)
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trim('-')
    return if (slug.isEmpty()) null else AUTO_PREFIX + slug
}

@Serializable
data class SkillParam(val name: String, val description: String = "")

data class SkillDraft(
    val id: String,
    val title: String,
    val description: String,
    val params: List<SkillParam>,
    val body: String,
    val fromWeb: Boolean,
)

sealed class AutoSkillWrite {
    data class Written(val id: String, val updated: Boolean) : AutoSkillWrite()
    data class Rejected(val reasons: List<String>) : AutoSkillWrite()
}

/** Writes, lists and rolls back auto-learned skills. [write] runs the code gate itself. */
class AutoSkillStore(private val dir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")) {
    private fun versions() = SkillVersionStore(
        VersionStore(dir.resolve(".versions")), project = false, legacyJsonlPath = dir.resolve(".versions.jsonl")
    )

    fun render(draft: SkillDraft): String {
        val tags = listOf(AUTO_LEARNED_TAG) + if (draft.fromWeb) listOf(FROM_WEB_TAG) else emptyList()
        val frontmatter = Yaml.default.encodeToString(
            SkillMetadata.serializer(),
            SkillMetadata(title = draft.title.oneLine(), description = draft.description.oneLine(), tags = tags)
        )
        val params = if (draft.params.isEmpty()) "" else
            "## Parameters\n" + draft.params.joinToString("\n") { "- `${it.name.oneLine()}`: ${it.description.oneLine()}" } + "\n\n"
        return "---\n$frontmatter\n---\n$params${draft.body.trim()}\n"
    }

    /** The code gate (ADR-030): the stricter check meant for content that didn't come straight from the user. */
    fun check(draft: SkillDraft): List<String> = buildList {
        if (!isAutoSkillId(draft.id)) add("id must match ${AUTO_ID.pattern} (got: ${draft.id})")
        if (draft.title.isBlank() || draft.body.isBlank()) add("title and body are required")
        // The description goes into the skill tool's definition in every later chat — keep it short.
        if (draft.title.length > MAX_TITLE) add("title is longer than $MAX_TITLE characters")
        if (draft.description.length > MAX_DESCRIPTION) add("description is longer than $MAX_DESCRIPTION characters")
        draft.params.filterNot { PARAM_NAME.matches(it.name) }.forEach { add("parameter name '${it.name}' must match ${PARAM_NAME.pattern}") }
        addAll(checkInstalledSkillContent(render(draft)))
    }

    fun write(draft: SkillDraft): AutoSkillWrite = synchronized(SkillVersionsLock) { writeLocked(draft) }

    private fun writeLocked(draft: SkillDraft): AutoSkillWrite {
        val problems = check(draft)
        if (problems.isNotEmpty()) return AutoSkillWrite.Rejected(problems)
        dir.createDirectories()
        val path = dir.resolve("${draft.id}.md")
        val updated = path.exists()
        if (updated && !isLearned(path)) {
            return AutoSkillWrite.Rejected(listOf("${draft.id} exists and isn't an auto-learned skill"))
        }
        val store = versions()
        // A file with no history (hand-edited, or older than versioning) gets its content kept first.
        if (updated && store.history(draft.id, false).isEmpty()) {
            store.record(SkillVersion(skillId = draft.id, project = false, content = path.readText()))
        }
        val content = render(draft)
        // Recorded before the file is written: a failed record leaves no unversioned skill live.
        store.record(SkillVersion(skillId = draft.id, project = false, content = content, trial = true))
        path.writeText(content)
        return AutoSkillWrite.Written(draft.id, updated)
    }

    /** Only files this feature wrote: an `auto-*` name alone could be a hand-written or installed skill. */
    fun list(): List<Pair<String, Skill>> = SkillRegistry.load(dir, dir).all()
        .filter { (id, skill) -> isAutoSkillId(id) && AUTO_LEARNED_TAG in skill.metadata.tags }

    private fun isLearned(path: Path): Boolean =
        runCatching { AUTO_LEARNED_TAG in SkillLoader().loadFile(path).metadata.tags }.getOrDefault(false)

    /** Restores the newest earlier version that differs from the file. Rolling back twice
     *  toggles between the last two versions. False when there's nothing to go back to. */
    fun rollback(id: String): Boolean = synchronized(SkillVersionsLock) { rollbackLocked(id) }

    private fun rollbackLocked(id: String): Boolean {
        if (!isAutoSkillId(id)) return false
        val path = dir.resolve("$id.md")
        if (!path.exists() || !isLearned(path)) return false
        val current = path.readText()
        val store = versions()
        val previous = store.history(id, false).firstOrNull { it.content != current } ?: return false
        path.writeText(previous.content)
        store.record(SkillVersion(skillId = id, project = false, content = previous.content))
        return true
    }
}

private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()
