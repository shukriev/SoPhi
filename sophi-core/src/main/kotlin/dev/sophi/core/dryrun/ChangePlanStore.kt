package dev.sophi.core.dryrun

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

private val planJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** One JSON file per plan, `<dir>/<id>.json`. Writes are atomic (temp file + move). */
class ChangePlanStore(val dir: Path = Path.of(System.getProperty("user.home"), ".sophi", "plans")) {

    fun save(plan: ChangePlan) {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, plan.id, ".tmp")
        Files.writeString(tmp, planJson.encodeToString(ChangePlan.serializer(), plan))
        Files.move(tmp, dir.resolve("${plan.id}.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun get(id: String): ChangePlan? {
        // An id is a file name, never a path.
        if (id.isBlank() || '/' in id || '\\' in id || id.startsWith(".")) return null
        val file = dir.resolve("$id.json")
        if (!file.exists()) return null
        return runCatching { planJson.decodeFromString(ChangePlan.serializer(), file.readText()) }.getOrNull()
    }

    /** Newest first; unreadable files are skipped. */
    fun list(): List<ChangePlan> {
        if (!dir.exists()) return emptyList()
        return dir.listDirectoryEntries("*.json")
            .mapNotNull { runCatching { planJson.decodeFromString(ChangePlan.serializer(), it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMs }
    }
}
