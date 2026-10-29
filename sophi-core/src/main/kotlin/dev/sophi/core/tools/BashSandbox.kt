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
    /** One value, swapped whole, so [wrap] never sees a half-switched sandbox. */
    private class State(val mode: SandboxMode, val network: Boolean, val image: String, val docker: DockerSandbox?)

    @Volatile private var state = State(SandboxMode.OFF, true, DEFAULT_DOCKER_IMAGE, null)
    @Volatile private var closed = false

    val mode: SandboxMode get() = state.mode
    val network: Boolean get() = state.network
    val image: String get() = state.image

    /** Null when switched; otherwise why [mode] can't run here, and nothing changed. */
    @Synchronized
    fun set(mode: SandboxMode = this.mode, network: Boolean = this.network, image: String = this.image): String? {
        if (image.startsWith("-")) return "a Docker image name can't start with '-'"
        val old = state
        var docker = old.docker
        when (mode) {
            SandboxMode.SEATBELT -> if (!SeatbeltSandbox.available) return "seatbelt isn't available on this machine (macOS only)"
            SandboxMode.DOCKER -> {
                if (':' in root.toString()) return "a workspace path with ':' can't be mounted in Docker"
                if (!DockerSandbox.available) return "Docker isn't running"
                // a new image or network needs a new container; started now, so a pull happens at switch time
                if (docker == null || network != old.network || image != old.image) {
                    val next = DockerSandbox(root, image, network)
                    try { next.start() } catch (e: Exception) { next.close(); return "Docker couldn't start $image: ${e.message}" }
                    docker = next
                }
            }
            SandboxMode.OFF -> {}
        }
        if (mode != SandboxMode.DOCKER) docker = null
        state = State(mode, network, image, docker)
        if (old.docker !== docker) old.docker?.close()
        return null
    }

    /** [command] wrapped for the current mode; throws rather than run a Docker-mode command on the host. */
    fun wrap(command: List<String>): List<String> {
        val s = state
        return when (s.mode) {
            SandboxMode.OFF -> command
            SandboxMode.SEATBELT -> seatbelt.wrap(command, s.network)
            SandboxMode.DOCKER -> checkNotNull(s.docker?.takeUnless { closed }) { "the Docker sandbox is closed" }.wrap(command)
        }
    }

    /** A command [lease]d to run; [release] when it has finished. */
    class Lease(val command: List<String>, val release: () -> Unit)

    /** Like [wrap], but a Docker container being switched away keeps running until this command is released. */
    fun lease(command: List<String>): Lease {
        val s = state
        if (s.mode != SandboxMode.DOCKER) return Lease(wrap(command)) {}
        val docker = checkNotNull(s.docker?.takeUnless { closed }) { "the Docker sandbox is closed" }
        return Lease(docker.acquire(command), docker::release)
    }

    @Synchronized
    override fun close() { closed = true; state.docker?.close() }
}
