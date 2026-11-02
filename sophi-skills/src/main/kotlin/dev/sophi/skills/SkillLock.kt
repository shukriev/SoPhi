package dev.sophi.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What was installed for one skill: where from, the git commit (null for a local path), and a
 * SHA-256 of the installed files ([skillContentHash]).
 */
@Serializable
data class SkillPin(val source: String, val commit: String? = null, val sha256: String)

/**
 * `skills.lock.json` next to the skills. Only skills SoPhi installed get an entry; a skill with
 * none (hand-written, written by Sophi, or installed before pinning existed) loads as before.
 */
object SkillLock {
    const val FILE = "skills.lock.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** A missing or unreadable lock reads as empty, so a damaged file never hides skills. */
    fun read(dir: Path): Map<String, SkillPin> = runCatching {
        dir.resolve(FILE).takeIf { it.exists() }?.let { json.decodeFromString<Map<String, SkillPin>>(it.readText()) }
    }.getOrNull() ?: emptyMap()

    fun write(dir: Path, pins: Map<String, SkillPin>) =
        dir.resolve(FILE).writeText(json.encodeToString<Map<String, SkillPin>>(pins.toSortedMap()))

    /** SoPhi's own writers call this after changing a skill, so a pinned skill they edit keeps loading. */
    fun repinIfPinned(dir: Path, id: String, by: String) {
        val pins = read(dir)
        if (id !in pins) return
        write(dir, pins + (id to SkillPin(source = by, sha256 = skillContentHash(dir, id))))
    }
}

/** SHA-256 over `<id>.md` plus every file in its installer-made bundle folder, in path order. */
fun skillContentHash(dir: Path, id: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(dir.resolve("$id.md").readBytes())
    val bundle = dir.resolve(id)
    if (bundle.isDirectory()) {
        bundle.toFile().walkTopDown().filter { it.isFile }.map { bundle.relativize(it.toPath()).toString() }.sorted()
            .forEach { rel -> digest.update(rel.toByteArray()); digest.update(bundle.resolve(rel).readBytes()) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
