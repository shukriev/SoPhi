package dev.sophi.context

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ProjectInfo(
    val id: String, val root: String, val name: String, val status: String, val global: Boolean,
    val lastScanAt: Long?, val fileCount: Int?, val partial: Boolean, val modules: List<String>
)

sealed interface RegisterResult {
    data class Registered(val project: ProjectInfo, val replaced: List<String>) : RegisterResult
    data class AlreadyCovered(val project: ProjectInfo) : RegisterResult
    data class Refused(val reason: String) : RegisterResult
}

/** Discovery guards (spec §3.3): consent-only, never the home folder or the filesystem root. */
object ProjectRegistry {
    fun projectId(root: Path): String =
        MessageDigest.getInstance("SHA-256").digest(root.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun resolveRoot(path: Path, autoOnly: Boolean): Path? {
        val p = runCatching { path.toRealPath() }.getOrNull() ?: return null
        findGitRoot(p)?.let { return it.toRealPath() }
        if (!autoOnly) return p
        val hasInstructions = INSTRUCTION_FILES.any { Files.exists(p.resolve(it)) } || Files.exists(p.resolve(".sophi/instructions.md"))
        return if (hasInstructions) p else null
    }

    fun refusal(root: Path): String? {
        val home = runCatching { Path.of(System.getProperty("user.home")).toRealPath() }.getOrNull()
        return when {
            root.parent == null -> "won't index the filesystem root"
            root == home -> "won't index your whole home folder — pick a project folder inside it"
            else -> null
        }
    }
}
