package dev.sophi.store.arcade

import com.arcadedb.ContextConfiguration
import com.arcadedb.GlobalConfiguration
import com.arcadedb.database.Database
import com.arcadedb.server.ArcadeDBServer
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * An ArcadeDB server bound to 127.0.0.1, rooted at [root] (databases under `root/databases`).
 * One password per root, kept in a 0600 `secret` file — the same trust boundary as the rest of
 * ~/.sophi. Studio isn't on the classpath and server plugins are off, so only the HTTP API is served.
 */
class LocalArcadeServer private constructor(
    private val server: ArcadeDBServer,
    val port: Int,
    val password: String
) : AutoCloseable {

    companion object {
        const val USER = "root"
        private const val PORT_ATTEMPTS = 3

        fun start(root: Path): LocalArcadeServer {
            useSophiArcadeLogConfig()
            Files.createDirectories(root)
            val password = readSecret(root) ?: newSecret(root)
            var lastError: Exception? = null
            // ponytail: pick-then-bind has a tiny race with other processes taking the port; three
            // attempts covers it. Bind to port 0 directly if ArcadeDB ever exposes the bound port.
            repeat(PORT_ATTEMPTS) {
                val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                val server = ArcadeDBServer(config(root, password, port))
                try {
                    server.start()
                    return LocalArcadeServer(server, port, password)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { server.stop() }
                }
            }
            throw IllegalStateException("ArcadeDB server could not start on loopback under $root", lastError)
        }

        internal fun readSecret(root: Path): String? =
            root.resolve("secret").takeIf { Files.exists(it) }
                ?.also { restrictToOwner(it) }
                ?.let { Files.readString(it).trim() }
                ?.takeIf { it.isNotBlank() }

        /** A missing secret means ArcadeDB's stored user hash belongs to an old password — drop it. */
        private fun newSecret(root: Path): String {
            Files.deleteIfExists(root.resolve("config").resolve("server-users.jsonl"))
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val file = root.resolve("secret")
            // Created 0600 from the start: no window where the password is readable by others.
            Files.deleteIfExists(file)
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(file, secret)
            return secret
        }

        private fun config(root: Path, password: String, port: Int) = ContextConfiguration().apply {
            setValue(GlobalConfiguration.SERVER_ROOT_PATH, root.toString())
            setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, root.resolve("databases").toString())
            setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, password)
            setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_HOST, "127.0.0.1")
            setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_PORT, port.toString())
            setValue(GlobalConfiguration.SERVER_PLUGINS, "")
            setValue(GlobalConfiguration.SERVER_METRICS, false)
        }
    }

    /** The named database, created on first use. Shares the server's open instance. */
    fun database(name: String): Database = server.getOrCreateDatabase(name)

    override fun close() = server.stop()
}

internal fun restrictToOwner(file: Path) {
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
}
