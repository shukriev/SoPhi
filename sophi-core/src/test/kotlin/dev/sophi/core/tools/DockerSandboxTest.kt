package dev.sophi.core.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

private fun BashTool.run(command: String) = runBlocking {
    execute(kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(command)) }.toString())
}

private fun docker(vararg args: String): String {
    val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
    p.waitFor(120, TimeUnit.SECONDS)
    return p.inputStream.bufferedReader().readText().trim()
}

class DockerSandboxTest : FunSpec({
    test("run args carry every limit") {
        val root = createTempDirectory("ws")
        val args = DockerSandbox(root, "img:1").runArgs()
        args.shouldContainInOrder("--cap-drop", "ALL")
        args.shouldContainInOrder("--security-opt", "no-new-privileges")
        args.shouldContainInOrder("--pids-limit", "512")
        args.shouldContainInOrder("--memory", "2g")
        args.shouldContainInOrder("--cpus", "2")
        args.takeLast(7) shouldBe listOf("-v", "$root:$root", "-w", "$root", "img:1", "sleep", "infinity")
        args shouldNotContain "--network"
        DockerSandbox(root, "img:1", network = false).runArgs().shouldContainInOrder("--network", "none")
    }

    test("a ':' in the workspace path is refused") {
        val root = createTempDirectory("ws").resolve("a:b").createDirectories()
        val sandbox = BashSandbox(root)
        sandbox.set(SandboxMode.DOCKER) shouldBe "a workspace path with ':' can't be mounted in Docker"
        sandbox.mode shouldBe SandboxMode.OFF
    }

    test("a closed DockerSandbox can't start again") {
        val sandbox = DockerSandbox(createTempDirectory("ws"))
        sandbox.close()
        shouldThrow<IllegalStateException> { sandbox.start() }
    }

    test("an image name starting with '-' is refused, so it can't pass docker an option") {
        val sandbox = BashSandbox(createTempDirectory("ws"))
        sandbox.set(image = "--privileged") shouldBe "a Docker image name can't start with '-'"
        sandbox.image shouldBe DEFAULT_DOCKER_IMAGE
    }

    context("with Docker").config(enabled = DockerSandbox.available) {
        test("a container whose pid now belongs to another process is removed; a live sandbox's is kept") {
            val me = ProcessHandle.current()
            val reused = docker("run", "-d", "--label", "sophi.pid=${me.pid()}", "--label", "sophi.started=1", DEFAULT_DOCKER_IMAGE, "sleep", "infinity")
            DockerSandbox(createTempDirectory("ws")).use { live ->
                live.start()
                DockerSandbox.removeOrphans()
                docker("ps", "-aq", "--no-trunc", "--filter", "id=$reused") shouldBe ""
                docker("ps", "-q", "--filter", "name=${live.name}") shouldNotBe ""
            }
        }

        test("a switch lets a running command finish before its container goes") {
            val ws = createTempDirectory("ws")
            BashSandbox(ws).use { sandbox ->
                sandbox.set(SandboxMode.DOCKER) shouldBe null
                val tool = BashTool(ws, sandbox)
                val out = runBlocking {
                    val running = async(Dispatchers.IO) { tool.execute("""{"command":"sleep 3; echo done"}""") }
                    delay(1500)
                    sandbox.set(network = false) shouldBe null
                    running.await()
                }
                out shouldContain "done"
            }
        }

        test("after close, a docker-mode command is refused instead of running on the host") {
            val sandbox = BashSandbox(createTempDirectory("ws"))
            sandbox.set(SandboxMode.DOCKER) shouldBe null
            sandbox.close()
            shouldThrow<IllegalStateException> { sandbox.wrap(listOf("sh", "-c", "x")) }
        }

        test("acceptance: /etc can't be written, a workspace write shows on the host, no network means no curl") {
            val ws = createTempDirectory("ws")
            BashSandbox(ws).use { sandbox ->
                sandbox.set(SandboxMode.DOCKER) shouldBe null
                val tool = BashTool(ws, sandbox)
                tool.run("touch /etc/x") shouldContain "Command exited with code"
                tool.run("echo hi > made")
                ws.resolve("made").readText().trim() shouldBe "hi"
                sandbox.set(network = false) shouldBe null
                tool.run("curl -sS -m 5 https://example.com") shouldContain "Command exited with code"
            }
        }

        test("a workspace path with quotes, spaces and parentheses works") {
            val ws = createTempDirectory("ws").resolve("it's a \"(dir)\"").createDirectories()
            BashSandbox(ws).use { sandbox ->
                sandbox.set(SandboxMode.DOCKER) shouldBe null
                BashTool(ws, sandbox).run("touch inside && echo made") shouldContain "made"
                ws.resolve("inside").toFile().exists() shouldBe true
            }
        }

        test("close removes the container") {
            val sandbox = DockerSandbox(createTempDirectory("ws"))
            sandbox.start()
            docker("ps", "-q", "--filter", "name=${sandbox.name}") shouldNotBe ""
            sandbox.close()
            docker("ps", "-aq", "--filter", "name=${sandbox.name}") shouldBe ""
        }

        test("a container left by a dead process is removed at the next start") {
            ProcessHandle.of(999999).isEmpty shouldBe true
            val orphan = docker("run", "-d", "--label", "sophi.pid=999999", DEFAULT_DOCKER_IMAGE, "sleep", "infinity")
            DockerSandbox(createTempDirectory("ws")).use { it.start() }
            docker("ps", "-aq", "--no-trunc", "--filter", "id=$orphan") shouldBe ""
        }
    }
})
