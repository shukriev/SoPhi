package dev.sophi.gateway.discord

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Who the bot is paired with, and their DM channel (`phone.json`). The token is never here. */
@Serializable
data class Pairing(val userId: String, val userName: String, val dmChannelId: String)

class DiscordState(private val path: Path) {
    @Volatile
    var pairing: Pairing? = runCatching { discordJson.decodeFromString(Pairing.serializer(), Files.readString(path)) }.getOrNull()
        private set

    fun pair(p: Pairing) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, discordJson.encodeToString(Pairing.serializer(), p))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        pairing = p
    }

    fun clear() { Files.deleteIfExists(path); pairing = null }
}

/** Where the bot token is kept: the host's secret store (the companion's Keychain). */
interface TokenStore {
    fun get(): String?
    fun save(token: String)
    fun remove()
}
