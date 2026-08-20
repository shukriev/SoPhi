package dev.sophi.skills

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class SkillVersionStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fold(): Map<String, SkillVersion> =
        if (!Files.exists(path)) emptyMap()
        else Files.readAllLines(path).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<SkillVersion>(it) }.getOrNull() }
            .associateBy { it.id }

    // fold()'s LinkedHashMap preserves append (oldest-first) order; reverse before the stable sort
    // so ties on `ts` (same millisecond) still resolve newest-recorded-first instead of file order.
    fun history(skillId: String, project: Boolean): List<SkillVersion> =
        fold().values.filter { it.skillId == skillId && it.project == project }
            .asReversed().sortedByDescending { it.ts }

    fun get(id: String): SkillVersion? = fold()[id]

    fun all(): List<SkillVersion> = fold().values.toList()

    @Synchronized
    fun record(version: SkillVersion): SkillVersion {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(version).replace("\n", " ")
        Files.write(path, (line + "\n").toByteArray(), CREATE, APPEND)
        return version
    }
}
