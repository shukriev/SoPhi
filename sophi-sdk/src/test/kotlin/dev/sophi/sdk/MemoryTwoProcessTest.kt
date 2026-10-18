package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Orthogonal vectors per topic, so two different memories never merge as near-duplicates. */
internal object TopicEmbeddings : EmbeddingProvider {
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { t ->
        FloatArray(4).also { it[if ("milk" in t) 0 else if ("mum" in t) 1 else 2] = 1f }
    }
}

/** A memory-enabled runtime on the shared store at [root], as the CLI or the companion builds it. */
internal fun sharedMemoryRuntime(root: Path, home: Path, dir: Path): SophiRuntime = RuntimeBuilder().apply {
    provider = mockk<dev.sophi.ai.api.LLMProvider>(relaxed = true)
    sessionsDir = dir.resolve("sessions"); skillsDir = dir.resolve("skills")
    memoryHome = home; memorySharedRoot = root
}.contextWindowTokens(100_000)
    .memory("topic", "http://ignored-by-override", embeddingProvider = TopicEmbeddings, onWarning = { System.err.println(it) })
    .build()

/** Child for [MemoryTwoProcessTest]: hosts, remembers one thing, then searches on cue and reports. */
object MemoryProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        ProcessHandle.current().parent().ifPresent { p -> p.onExit().thenRun { exitProcess(0) } }
        val (root, home, base) = args.map { Path.of(it) }
        val palace = sharedMemoryRuntime(root, home, base.resolve("child")).memoryPlugin!!.palace()!!
        checkNotNull(runBlocking { palace.rememberCommitment("buy milk", "child", System.currentTimeMillis()) }) { "child write failed" }
        Files.writeString(base.resolve("ready"), "ok")
        while (!Files.exists(base.resolve("go"))) Thread.sleep(50)
        val found = runBlocking { palace.search("buy milk", 5) }.joinToString("\n") { it.text }
        val tmp = Files.writeString(base.resolve("found.tmp"), found)
        Files.move(tmp, base.resolve("found"), StandardCopyOption.ATOMIC_MOVE)
        Thread.sleep(Long.MAX_VALUE)
    }
}

class MemoryTwoProcessTest : FunSpec({
    fun awaitFile(file: Path, timeoutMs: Long, child: Process) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!Files.exists(file)) {
            check(child.isAlive) { "child exited early with ${child.exitValue()} (see ${file.resolveSibling("child.log")})" }
            check(System.currentTimeMillis() < deadline) { "child not ready after $timeoutMs ms (see ${file.resolveSibling("child.log")})" }
            Thread.sleep(50)
        }
    }

    test("two processes both have working memory at once: each writes, and each finds both memories") {
        val base = tempdir().toPath()
        val root = base.resolve("db"); val home = base.resolve("memory")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MemoryProcessMain::class.java.name,
            root.toString(), home.toString(), base.toString())
            .redirectErrorStream(true).redirectOutput(base.resolve("child.log").toFile()).start()
        try {
            awaitFile(base.resolve("ready"), 60_000, child)
            Json.parseToJsonElement(Files.readString(root.resolve("host.json"))).jsonObject["pid"]!!.jsonPrimitive.long shouldBe child.pid()
            val rt = sharedMemoryRuntime(root, home, base.resolve("parent"))   // this process is the client
            try {
                val palace = rt.memoryPlugin.shouldNotBeNull().palace().shouldNotBeNull()
                runBlocking { palace.rememberCommitment("call mum", "parent", System.currentTimeMillis()) }.shouldNotBeNull()
                runBlocking { palace.search("call mum", 5) }.map { it.text } shouldContainExactlyInAnyOrder listOf("buy milk", "call mum")
                Files.writeString(base.resolve("go"), "go")
                awaitFile(base.resolve("found"), 30_000, child)
                Files.readAllLines(base.resolve("found")) shouldContainExactlyInAnyOrder listOf("buy milk", "call mum")
            } finally { rt.close() }
        } finally { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
    }
})
