package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ArcadeHostTwoProcessTest : FunSpec({

    fun spawnHost(root: Path, ready: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), HostProcessMain::class.java.name, root.toString(), ready.toString())
            .redirectErrorStream(true)
            .redirectOutput(root.resolveSibling("child.log").toFile())
            .start()
    }

    fun awaitFile(file: Path, timeoutMs: Long, child: Process) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!Files.exists(file)) {
            check(child.isAlive) { "host child exited early with ${child.exitValue()} (see ${file.resolveSibling("child.log")})" }
            check(System.currentTimeMillis() < deadline) { "host child not ready after $timeoutMs ms (see ${file.resolveSibling("child.log")})" }
            Thread.sleep(50)
        }
    }

    test("another process hosts; this one is a client; after SIGKILL this one takes over with the data intact") {
        val base = tempdir().toPath()
        val root = base.resolve("db")
        val ready = base.resolve("ready")
        val child = spawnHost(root, ready)
        try {
            awaitFile(ready, 30_000, child)
            ArcadeHost.open(root, database = "ctx", schemaVersion = 1, engineVersion = "parent").use { me ->
                me.role shouldBe Role.CLIENT
                me.withStore { it.upsertDocument("Note", "from-client", mapOf("who" to "parent")) }

                child.destroyForcibly()
                child.waitFor(10, TimeUnit.SECONDS) shouldBe true

                val started = System.nanoTime()
                val ids = me.withStore { s -> s.documents("Note").map { it["id"] } }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                println("failover after SIGKILL took $elapsedMs ms")
                me.role shouldBe Role.HOST
                ids shouldContainExactlyInAnyOrder listOf("from-host", "from-client")
                elapsedMs shouldBeLessThan 2_000L
            }
        } finally {
            child.destroyForcibly()
        }
    }
})
