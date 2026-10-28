package dev.sophi.core.tools

import java.nio.file.Path

enum class SandboxMode { OFF, SEATBELT, DOCKER }

const val DEFAULT_DOCKER_IMAGE = "buildpack-deps:bookworm-scm"

/**
 * Where `bash` runs, switchable while a session runs: [BashTool] asks [wrap] once per command, so a
 * [set] applies from the next command. A second wall behind [bashReachesOutside], never instead of it.
 */
class BashSandbox(
    private val root: Path,
    private val seatbelt: SeatbeltSandbox = SeatbeltSandbox.forWorkspace(root),
) : AutoCloseable {
    @Volatile var mode: SandboxMode = SandboxMode.OFF; private set
    @Volatile var network: Boolean = true; private set
    @Volatile var image: String = DEFAULT_DOCKER_IMAGE; private set

    /** Null when switched; otherwise why [mode] can't run here, and nothing changed. */
    @Synchronized
    fun set(mode: SandboxMode = this.mode, network: Boolean = this.network, image: String = this.image): String? {
        when (mode) {
            SandboxMode.SEATBELT -> if (!SeatbeltSandbox.available) return "seatbelt isn't available on this machine (macOS only)"
            SandboxMode.DOCKER -> return "Docker sandbox isn't built yet"
            SandboxMode.OFF -> {}
        }
        this.mode = mode; this.network = network; this.image = image
        return null
    }

    fun wrap(command: List<String>): List<String> = when (mode) {
        SandboxMode.OFF -> command
        SandboxMode.SEATBELT -> seatbelt.wrap(command, network)
        SandboxMode.DOCKER -> command
    }

    override fun close() {}
}
