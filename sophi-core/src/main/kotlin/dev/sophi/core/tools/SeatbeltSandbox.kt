package dev.sophi.core.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"
/** Name lookups go through this socket, not IP: with the network off, DNS would otherwise still leak data out. */
private const val MDNS_SOCKET = "/private/var/run/mDNSResponder"

/** Where Docker's API socket lives: the system path, Docker Desktop's, and `DOCKER_HOST` when it is a Unix socket. */
fun dockerSockets(home: Path = Path.of(System.getProperty("user.home"))): List<Path> =
    listOf(Path.of("/var/run/docker.sock"), home.resolve(".docker/run/docker.sock")) +
        listOfNotNull(System.getenv("DOCKER_HOST")?.removePrefix("unix://")?.takeIf { it.startsWith("/") }?.let(Path::of))

/**
 * macOS's built-in sandbox around each `bash` command (the approach Codex CLI uses): reads anywhere,
 * writes only under [writable] and /dev. A second wall behind [bashReachesOutside], which can't see a
 * path built at run time. Paths reach the profile as `-D` parameters, never spliced into its text,
 * so a workspace name with quotes or parentheses can't change the rules.
 *
 * `sandbox-exec` is deprecated by Apple but still shipped; when it's gone ([available] false) commands
 * run as before.
 */
class SeatbeltSandbox(writable: List<Path>, blockedSockets: List<Path> = dockerSockets()) {
    /** Symlinks resolved: the sandbox matches real paths (/var/folders is /private/var/folders). */
    val writable: List<Path> = writable.map { runCatching { it.toRealPath() }.getOrDefault(it.toAbsolutePath().normalize()) }

    /**
     * Unix sockets no command may connect to, real paths (the rule matches the resolved one): Docker's,
     * because `docker run -v /:/host` would step past every rule here.
     */
    val blockedSockets: List<Path> = blockedSockets.map { runCatching { it.toRealPath() }.getOrDefault(it) }.distinct()

    /** [command] (a program and its arguments) run inside the sandbox; [network] false blocks IP traffic and name lookups. */
    fun wrap(command: List<String>, network: Boolean = true): List<String> {
        val params = writable.flatMapIndexed { i, p -> listOf("-D", "W$i=$p") } +
            blockedSockets.flatMapIndexed { i, p -> listOf("-D", "S$i=$p") }
        val sockets = blockedSockets.indices.joinToString("") { "(deny network-outbound (remote unix-socket (path-literal (param \"S$it\"))))" }
        val rules = writable.indices.joinToString(" ") { "(subpath (param \"W$it\"))" }
        val profile = "(version 1)(allow default)(deny file-write*)(allow file-write* (subpath \"/dev\") $rules)" + sockets +
            if (network) "" else "(deny network-outbound (remote ip))(deny network-outbound (remote unix-socket (path-literal \"$MDNS_SOCKET\")))"
        return listOf(SANDBOX_EXEC) + params + listOf("-p", profile) + command
    }

    companion object {
        /** `sandbox-exec` is there and works on this machine. */
        val available: Boolean by lazy {
            Files.isExecutable(Path.of(SANDBOX_EXEC)) && runCatching {
                val p = ProcessBuilder(SANDBOX_EXEC, "-p", "(version 1)(allow default)", "/usr/bin/true").start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        }

        /**
         * The workspace, the temp folders, and the caches package managers and builds write to, so
         * `npm install` or a Gradle build still works. Only the cache folders: `~/.gradle/init.d` or
         * `~/.m2/settings.xml` would let a command plant code that later runs outside the sandbox.
         */
        fun forWorkspace(root: Path, home: Path = Path.of(System.getProperty("user.home"))) = SeatbeltSandbox(
            listOf(root, Path.of("/private/tmp"), Path.of("/private/var/folders"), Path.of(System.getProperty("java.io.tmpdir"))) +
                listOf("Library/Caches", ".cache", ".npm", ".gradle/caches", ".m2/repository").map(home::resolve)
        )
    }
}
