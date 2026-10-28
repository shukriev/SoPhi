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
    @Volatile private var docker: DockerSandbox? = null

    /** Null when switched; otherwise why [mode] can't run here, and nothing changed. */
    @Synchronized
    fun set(mode: SandboxMode = this.mode, network: Boolean = this.network, image: String = this.image): String? {
        when (mode) {
            SandboxMode.SEATBELT -> if (!SeatbeltSandbox.available) return "seatbelt isn't available on this machine (macOS only)"
            SandboxMode.DOCKER -> {
                if (':' in root.toString()) return "a workspace path with ':' can't be mounted in Docker"
                if (!DockerSandbox.available) return "Docker isn't running"
                // a new image or network needs a new container; started now, so a pull happens at switch time
                if (docker == null || network != this.network || image != this.image) {
                    val next = DockerSandbox(root, image, network)
                    try { next.start() } catch (e: Exception) { next.close(); return "Docker couldn't start $image: ${e.message}" }
                    docker?.close()
                    docker = next
                }
            }
            SandboxMode.OFF -> {}
        }
        if (mode != SandboxMode.DOCKER) { docker?.close(); docker = null }
        this.mode = mode; this.network = network; this.image = image
        return null
    }

    fun wrap(command: List<String>): List<String> = when (mode) {
        SandboxMode.OFF -> command
        SandboxMode.SEATBELT -> seatbelt.wrap(command, network)
        SandboxMode.DOCKER -> docker?.wrap(command) ?: command
    }

    @Synchronized
    override fun close() { docker?.close(); docker = null }
}
