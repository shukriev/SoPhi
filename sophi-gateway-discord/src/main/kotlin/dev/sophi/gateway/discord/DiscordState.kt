package dev.sophi.gateway.discord

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Who the bot is paired with, and their DM channel. The owner's pairing is `phone.json`;
 * household members (each with a [personId]) are in `phone-members.json`. The token is never here.
 */
@Serializable
data class Pairing(val userId: String, val userName: String, val dmChannelId: String, val personId: String? = null)

class DiscordState(private val path: Path) {
    private val membersPath = path.resolveSibling("phone-members.json")
    private val membersSerializer = ListSerializer(Pairing.serializer())

    @Volatile
    var pairing: Pairing? = runCatching { discordJson.decodeFromString(Pairing.serializer(), Files.readString(path)) }.getOrNull()
        private set

    @Volatile
    var members: List<Pairing> = runCatching { discordJson.decodeFromString(membersSerializer, Files.readString(membersPath)) }
        .getOrElse { emptyList() }.filter { it.personId != null }
        private set

    fun pair(p: Pairing) {
        write(path, discordJson.encodeToString(Pairing.serializer(), p))
        pairing = p
    }

    /** Pairs a household member; replaces an earlier pairing of the same person or Discord user. */
    @Synchronized
    fun pairMember(p: Pairing) {
        requireNotNull(p.personId) { "a member pairing needs a personId" }
        val next = members.filter { it.personId != p.personId && it.userId != p.userId } + p
        write(membersPath, discordJson.encodeToString(membersSerializer, next))
        members = next
    }

    @Synchronized
    fun unpairMember(personId: String): Boolean {
        val next = members.filter { it.personId != personId }
        if (next.size == members.size) return false
        write(membersPath, discordJson.encodeToString(membersSerializer, next))
        members = next
        return true
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(path); Files.deleteIfExists(membersPath)
        pairing = null; members = emptyList()
    }

    private fun write(target: Path, text: String) {
        target.parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Where the bot token is kept: the host's secret store (the companion's Keychain). */
interface TokenStore {
    fun get(): String?
    fun save(token: String)
    fun remove()
}
