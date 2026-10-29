package dev.sophi.core.tools

import dev.sophi.core.session.SessionIdContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class BashJobsTest : FunSpec({
    class Fx {
        val root = tempdir().toPath()
        val home = tempdir().toPath()
        val jobs = BashJobs(home)
        val bash = BashTool(root, jobs = jobs)
        fun bg(cmd: String) = runBlocking { withContext(SessionIdContext("s")) { bash.execute("""{"command":${kotlinx.serialization.json.JsonPrimitive(cmd)},"background":true}""") } }
    }

    test("background start returns at once with an id") {
        val f = Fx()
        val t0 = System.currentTimeMillis()
        val out = f.bg("echo hi; sleep 5")
        (System.currentTimeMillis() - t0 < 3_000) shouldBe true
        out shouldContain "job j1 started"
        out shouldContain "hi"
        f.jobs.close()
    }

    test("bash_output returns only new output and the status") {
        val f = Fx()
        f.bg("sleep 2.5; for i in 1 2 3; do echo \$i; sleep 0.3; done")
        val seen = StringBuilder()
        runBlocking {
            repeat(20) {
                val o = f.jobs.output("s", "j1", waitSeconds = 5)
                seen.append(o.substringAfter('\n')).append('\n')
                if (o.startsWith("exited 0")) return@runBlocking
            }
        }
        seen.lines().filter { it.isNotBlank() } shouldBe listOf("1", "2", "3")
    }

    test("tail is bounded") {
        val f = Fx()
        f.bg("sleep 2.5; seq 1 1000")
        val o = runBlocking { f.jobs.output("s", "j1", waitSeconds = 10) }
        val body = o.lines().drop(1).filter { it.isNotBlank() && !it.contains("earlier lines in") }
        body.size shouldBe 200
        body.last() shouldBe "1000"
        o shouldContain "800 earlier lines in"
    }

    test("wait returns early on new output") {
        val f = Fx()
        f.bg("sleep 3; echo x; sleep 30")
        val t0 = System.currentTimeMillis()
        val o = runBlocking { f.jobs.output("s", "j1", waitSeconds = 10) }
        (System.currentTimeMillis() - t0 < 5_000) shouldBe true
        o shouldContain "x"
        f.jobs.close()
    }

    test("kill stops it and marks killed") {
        val f = Fx()
        f.bg("sleep 60")
        f.jobs.kill("s", "j1") shouldContain "killed"
        f.jobs.list().single().status shouldBe "killed"
    }

    test("another session's job is not found") {
        val f = Fx()
        f.bg("sleep 30")
        runBlocking { f.jobs.output("other", "j1") } shouldBe "Error: no such job j1"
        f.jobs.kill("other", "j1") shouldBe "Error: no such job j1"
        f.jobs.kill(null, "j1") shouldContain "killed"
    }

    test("initial output is a bounded tail") {
        val f = Fx()
        val out = f.bg("seq 1 1000")
        out shouldContain "800 earlier lines in"
        out.lines().count { it.trim().toIntOrNull() != null } shouldBe 200
    }

    test("ninth job is refused with the running list") {
        val f = Fx()
        repeat(8) { f.bg("sleep 30") }
        val out = f.bg("sleep 30")
        out shouldStartWith "Error:"
        out shouldContain "j1"
        out shouldContain "j8"
        f.jobs.close()
    }

    test("close kills the whole process tree") {
        val f = Fx()
        val before = ProcessHandle.current().descendants().toList().toSet()
        f.bg("sleep 61 & sleep 61")
        val tree = ProcessHandle.current().descendants().toList().filter { it !in before && it.isAlive }
        (tree.isNotEmpty()) shouldBe true
        f.jobs.close()
        Thread.sleep(300)
        tree.none { it.isAlive } shouldBe true
    }

    test("another process's live dir survives startup") {
        val home = tempdir().toPath()
        val own = home.resolve(ProcessHandle.current().pid().toString()).also { Files.createDirectories(it) }.resolve("x.log").also { it.writeText("x") }
        val dead = home.resolve("999999999").also { Files.createDirectories(it) }.resolve("y.log").also { it.writeText("y") }
        BashJobs(home).close()
        own.exists() shouldBe true
        dead.parent.exists() shouldBe false
    }

    test("docker mode refuses background") {
        val sandbox = io.mockk.mockk<BashSandbox>(relaxed = true)
        io.mockk.every { sandbox.mode } returns SandboxMode.DOCKER
        val jobs = BashJobs(tempdir().toPath())
        val out = runBlocking { BashTool(tempdir().toPath(), sandbox, jobs).execute("""{"command":"sleep 1","background":true}""") }
        out shouldStartWith "Error: background jobs don't run in the Docker sandbox"
        jobs.list().size shouldBe 0
    }

    test("without jobs, background is refused") {
        runBlocking { BashTool(tempdir().toPath()).execute("""{"command":"sleep 1","background":true}""") } shouldBe
            "Error: background jobs aren't available here"
    }

    test("bash_output and bash_kill are deferred and safe") {
        val jobs = BashJobs(tempdir().toPath())
        listOf(BashOutputTool(jobs), BashKillTool(jobs)).forEach {
            it.deferred shouldBe true
            it.riskLevel("{}") shouldBe RiskLevel.SAFE
        }
    }
})
