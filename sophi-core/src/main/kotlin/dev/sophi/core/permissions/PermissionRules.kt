package dev.sophi.core.permissions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
enum class Effect {
    @SerialName("allow") ALLOW,
    @SerialName("ask") ASK,
    @SerialName("deny") DENY,
}

/** [tool] is a glob over tool names; a null [pattern] matches every call of the tool. */
@Serializable
data class PermissionRule(val tool: String, val pattern: String? = null, val effect: Effect)

/** What a call touches, for matching: a bash part, a path, a URL. [allowable] = false: no allow rule may match it (R4). */
data class PermissionSubject(val text: String, val allowable: Boolean = true)

class PermissionRulesException(val path: Path, message: String) : RuntimeException("$path: $message")

class PermissionRules(
    global: List<PermissionRule>,
    project: List<PermissionRule>,
    private val projectFile: Path?,
    private val trustFile: Path?,
    trusted: Set<PermissionRule>,
    /** The project file is the global file: its rules are global and "always" appends to it. */
    private val sameFile: Boolean = false,
) {
    private class Snapshot(
        val global: List<PermissionRule>,
        val project: List<PermissionRule>,
        val trusted: Set<PermissionRule>,
    ) {
        val active = global + project.filter { it.effect != Effect.ALLOW || it in trusted }
        val untrusted = project.filter { it.effect == Effect.ALLOW && it !in trusted }
    }

    @Volatile
    private var snap = Snapshot(global, project, trusted)

    val global: List<PermissionRule> get() = snap.global

    /** Rules in effect: global, project deny/ask, trusted project allow. */
    val active: List<PermissionRule> get() = snap.active

    /** Project allow rules not trusted yet (R2). */
    val untrusted: List<PermissionRule> get() = snap.untrusted

    /** Null = no rule applies (the existing flow decides). [subjects] null = couldn't be computed (R3). */
    fun decide(toolName: String, subjects: List<PermissionSubject>?): Effect? {
        val rules = snap.active.filter { globMatches(it.tool, toolName) }
        fun any(effect: Effect, applies: (PermissionRule) -> Boolean) = rules.any { it.effect == effect && applies(it) }
        if (subjects == null) {
            return when {
                any(Effect.DENY) { true } -> Effect.DENY
                any(Effect.ASK) { true } -> Effect.ASK
                else -> null
            }
        }
        if (subjects.isEmpty()) {
            val applies = { r: PermissionRule -> r.pattern == null || r.pattern == "*" }
            return Effect.entries.reversed().firstOrNull { any(it, applies) }
        }
        fun matches(r: PermissionRule, s: PermissionSubject) = r.pattern == null || globMatches(r.pattern, s.text)
        fun anyMatch(effect: Effect) = subjects.any { s -> any(effect) { matches(it, s) } }
        return when {
            anyMatch(Effect.DENY) -> Effect.DENY
            anyMatch(Effect.ASK) -> Effect.ASK
            subjects.all { s -> s.allowable && any(Effect.ALLOW) { matches(it, s) } } -> Effect.ALLOW
            else -> null
        }
    }

    /** Trusts every project allow rule now in the file; persists the trust file. */
    @Synchronized
    fun trustProject() {
        val file = projectFile ?: return
        val trustPath = trustFile ?: return
        if (sameFile) return
        val current = snap
        val trusted = current.trusted + current.project.filter { it.effect == Effect.ALLOW }
        saveTrust(trustPath, file, trusted)
        snap = Snapshot(current.global, current.project, trusted)
    }

    /** "Always allow": appends [rules] to the project file (created if missing) and trusts them. */
    @Synchronized
    fun addProjectAllows(rules: List<PermissionRule>) {
        val file = projectFile ?: throw IllegalStateException("no project file")
        // ponytail: read-modify-write is not locked across processes; a concurrent Sophi can lose a rule, add a file lock if it matters
        val onDisk = readRules(file) + rules
        writeAtomic(file, json.encodeToString(ruleList, onDisk))
        val current = snap
        if (sameFile) {
            snap = Snapshot(onDisk, current.project, current.trusted)
            return
        }
        val trusted = current.trusted + rules
        saveTrust(trustFile ?: return, file, trusted)
        snap = Snapshot(current.global, onDisk, trusted)
    }

    private fun saveTrust(trustPath: Path, file: Path, trusted: Set<PermissionRule>) {
        val all = readTrust(trustPath) + (key(file) to trusted.toList())
        writeAtomic(trustPath, json.encodeToString(trustMap, all))
    }

    companion object {
        private val json = Json { prettyPrint = true }
        private val ruleList = ListSerializer(PermissionRule.serializer())
        private val trustMap = MapSerializer(String.serializer(), ruleList)

        val EMPTY = PermissionRules(emptyList(), emptyList(), null, null, emptySet())

        /** Missing files = no rules. Malformed JSON, unknown effect or blank tool → PermissionRulesException (R5). */
        fun load(globalFile: Path, projectFile: Path, trustFile: Path): PermissionRules {
            val global = readRules(globalFile)
            if (projectFile.toAbsolutePath().normalize() == globalFile.toAbsolutePath().normalize()) {
                return PermissionRules(global, emptyList(), projectFile, trustFile, emptySet(), sameFile = true)
            }
            val trusted = readTrust(trustFile)[key(projectFile)].orEmpty().toSet()
            return PermissionRules(global, readRules(projectFile), projectFile, trustFile, trusted)
        }

        /** The rules "always allow" writes for these subjects: one exact rule per subject, a tool-wide rule
         *  when the tool has no subjects, or null when "always" can't be offered (subjects null, any not allowable). */
        fun alwaysAllowRules(toolName: String, subjects: List<PermissionSubject>?): List<PermissionRule>? {
            if (subjects == null || subjects.any { !it.allowable }) return null
            val tool = globEscape(toolName)
            if (subjects.isEmpty()) return listOf(PermissionRule(tool, null, Effect.ALLOW))
            return subjects.map { it.text }.distinct().map { PermissionRule(tool, globEscape(it), Effect.ALLOW) }
        }

        private fun key(projectFile: Path) = projectFile.toAbsolutePath().normalize().toString()

        private fun <T> parse(path: Path, block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw PermissionRulesException(path, e.message ?: "unreadable")
        } catch (e: IllegalArgumentException) { // SerializationException is one
            throw PermissionRulesException(path, e.message ?: "malformed")
        }

        private fun readRules(path: Path): List<PermissionRule> {
            if (!path.exists()) return emptyList()
            val rules = parse(path) { json.decodeFromString(ruleList, path.readText()) }
            if (rules.any { it.tool.isBlank() }) throw PermissionRulesException(path, "rule with a blank tool")
            return rules
        }

        private fun readTrust(path: Path): Map<String, List<PermissionRule>> {
            if (!path.exists()) return emptyMap()
            return parse(path) { json.decodeFromString(trustMap, path.readText()) }
        }

        // Stage to a temp file in the same directory, then atomically rename over the target.
        private fun writeAtomic(target: Path, content: String) {
            target.parent.createDirectories()
            val tmp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
            try {
                Files.writeString(tmp, content)
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
