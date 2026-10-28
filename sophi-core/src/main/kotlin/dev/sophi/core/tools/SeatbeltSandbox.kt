package dev.sophi.core.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

/**
 * macOS's built-in sandbox around each `bash` command (the approach Codex CLI uses): reads anywhere,
 * writes only under [writable] and /dev. A second wall behind [bashReachesOutside], which can't see a
 * path built at run time. Paths reach the profile as `-D` parameters, never spliced into its text,
 * so a workspace name with quotes or parentheses can't change the rules.
 *
 * `sandbox-exec` is deprecated by Apple but still shipped; when it's gone ([available] false) commands
 * run as before.
 */
class SeatbeltSandbox(writable: List<Path>) {
    /** Symlinks resolved: the sandbox matches real paths (/var/folders is /private/var/folders). */
    val writable: List<Path> = writable.map { runCatching { it.toRealPath() }.getOrDefault(it.toAbsolutePath().normalize()) }

    /** [command] (a program and its arguments) run inside the sandbox; [network] false blocks IP traffic (Unix sockets still work). */
    fun wrap(command: List<String>, network: Boolean = true): List<String> {
        val params = writable.flatMapIndexed { i, p -> listOf("-D", "W$i=$p") }
        val rules = writable.indices.joinToString(" ") { "(subpath (param \"W$it\"))" }
        val profile = "(version 1)(allow default)(deny file-write*)(allow file-write* (subpath \"/dev\") $rules)" +
            if (network) "" else "(deny network-outbound (remote ip))"
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
