package dev.sophi.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class Role { OWNER, MEMBER }

/** [role] defaults to OWNER so an older people.json, whose entries have no role, still reads as the owner. */
@Serializable
data class Person(val id: String, val name: String, val role: Role = Role.OWNER)

/** Who SoPhi serves: the owner (always first) and any household members. */
class People(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(Person.serializer())

    private fun read(): List<Person> =
        if (Files.exists(path)) json.decodeFromString(serializer, Files.readString(path)) else emptyList()

    private fun write(list: List<Person>) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(serializer, list))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun owner(): Person {
        read().firstOrNull()?.let { return it }
        return Person(UUID.randomUUID().toString(), "You").also { write(listOf(it)) }
    }

    @Synchronized
    fun all(): List<Person> { owner(); return read() }

    @Synchronized
    fun get(id: String): Person? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String): Person {
        val list = all()
        return Person(UUID.randomUUID().toString(), name, Role.MEMBER).also { write(list + it) }
    }

    /** False for the owner or an unknown id. */
    @Synchronized
    fun remove(id: String): Boolean {
        val list = all()
        if (list.first().id == id || list.none { it.id == id }) return false
        write(list.filter { it.id != id })
        return true
    }
}
