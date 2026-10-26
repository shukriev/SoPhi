package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.pathSubject
import dev.sophi.core.undo.FileMove
import dev.sophi.core.undo.RoundChanges
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Serializable
private data class MoveArgs(val from: String, val to: String)

@Serializable
private data class DeleteArgs(val path: String)

private val json = Json { ignoreUnknownKeys = true }

/**
 * `~` and `~/…` expand to [home], absolute paths stay, anything else is relative to [root].
 * Unlike write_file these may leave the root on purpose (organising ~/Downloads); ruleVerdict flags that.
 */
private fun resolveUserPath(root: Path, home: Path, raw: String): Path = when {
    raw == "~" -> home
    raw.startsWith("~/") -> home.resolve(raw.substring(2))
    else -> root.resolve(raw)
}.normalize()

private suspend fun moveAndRecord(root: Path, home: Path, from: Path, to: Path) {
    // `/`, the home and the workspace itself (or anything holding them) are never moved, nor trashed
    require(listOf(root, home).none { it.toAbsolutePath().normalize().startsWith(from) }) {
        "Refusing to move $from: it is /, your home folder, or holds the workspace"
    }
    require(from.exists()) { "No such file or directory: $from" }
    require(!to.exists()) { "Destination already exists: $to" }
    to.parent?.createDirectories()
    Files.move(from, to)
    coroutineContext[RoundChanges]?.record(FileMove(from.toString(), to.toString()))
}

private fun worst(a: RuleVerdict, b: RuleVerdict): RuleVerdict = when {
    RuleVerdict.HIGH_RISK in listOf(a, b) -> RuleVerdict.HIGH_RISK
    RuleVerdict.UNKNOWN in listOf(a, b) -> RuleVerdict.UNKNOWN
    else -> RuleVerdict.LOW_RISK
}

class MoveFileTool(
    private val root: Path = Path.of("").toAbsolutePath(),
    private val home: Path = Path.of(System.getProperty("user.home")),
) : Tool {

    override val name = "move_file"
    override val description = "Move or rename a file or directory. Never overwrites an existing destination. The move can be undone with /undo."
    override val parametersJson = """
        {"type":"object","properties":{"from":{"type":"string","description":"Existing path; relative to the working directory, absolute, or starting with ~/"},"to":{"type":"string","description":"New path, same forms; must not exist"}},"required":["from","to"]}
    """.trimIndent()

    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE

    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val args = runCatching { json.decodeFromString<MoveArgs>(argumentsJson) }.getOrNull()
            ?: return RuleVerdict.LOW_RISK
        return worst(
            classifyPathRisk(root, resolveUserPath(root, home, args.from).toString()),
            classifyPathRisk(root, resolveUserPath(root, home, args.to).toString()),
        )
    }

    override fun confirmationPreview(argumentsJson: String): String? =
        runCatching { json.decodeFromString<MoveArgs>(argumentsJson) }.getOrNull()?.let { "Move ${it.from} → ${it.to}" }

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        runCatching { json.decodeFromString<MoveArgs>(argumentsJson) }.getOrNull()
            ?.let { args ->
                val subjects = listOf(args.from, args.to).map { pathSubject(root, resolveUserPath(root, home, it)) }
                if (subjects.any { it == null }) null else subjects.filterNotNull()
            }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<MoveArgs>(argumentsJson)
        val from = resolveUserPath(root, home, args.from)
        val to = resolveUserPath(root, home, args.to)
        moveAndRecord(root, home, from, to)
        return "Moved $from to $to"
    }
}

class DeleteFileTool(
    private val root: Path = Path.of("").toAbsolutePath(),
    private val trashDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "trash"),
    private val home: Path = Path.of(System.getProperty("user.home")),
) : Tool {

    override val name = "delete_file"
    override val description = "Delete a file or directory by moving it to the SoPhi trash. The delete can be undone with /undo."
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to delete; relative to the working directory, absolute, or starting with ~/"}},"required":["path"]}
    """.trimIndent()

    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE

    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val args = runCatching { json.decodeFromString<DeleteArgs>(argumentsJson) }.getOrNull()
            ?: return RuleVerdict.LOW_RISK
        return classifyPathRisk(root, resolveUserPath(root, home, args.path).toString())
    }

    override fun confirmationPreview(argumentsJson: String): String? =
        runCatching { json.decodeFromString<DeleteArgs>(argumentsJson) }.getOrNull()?.let { "Move ${it.path} to the SoPhi trash" }

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        runCatching { json.decodeFromString<DeleteArgs>(argumentsJson) }.getOrNull()
            ?.let { pathSubject(root, resolveUserPath(root, home, it.path)) }?.let { listOf(it) }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<DeleteArgs>(argumentsJson)
        val path = resolveUserPath(root, home, args.path)
        val slot = trashDir.resolve("${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
        moveAndRecord(root, home, path, slot.resolve(path.fileName.toString()))
        return "Moved $path to the SoPhi trash (/undo restores it)"
    }
}
