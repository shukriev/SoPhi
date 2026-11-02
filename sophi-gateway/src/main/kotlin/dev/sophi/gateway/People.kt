package dev.sophi.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class Person(val id: String, val name: String)

/** Who SoPhi serves. One entry, the owner, until several people share one SoPhi. */
class People(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(Person.serializer())

    @Synchronized
    fun owner(): Person {
        if (Files.exists(path)) json.decodeFromString(serializer, Files.readString(path)).firstOrNull()?.let { return it }
        val owner = Person(UUID.randomUUID().toString(), "You")
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(serializer, listOf(owner)))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return owner
    }
}
