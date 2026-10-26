package dev.sophi.core.audit

import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

private fun entry(i: Int = 0) = AuditEntry(
    ts = 1000L + i, actor = LOCAL_ACTOR, session = "s", tool = "bash", risk = RiskLevel.CAUTION,
    args = "{}", decision = "allowed", rule = "r", outcome = "ok", result = "done", durationMs = 5,
)

class JsonlAuditSinkTest : FunSpec({
    test("record appends one JSON line per entry") {
        val file = Files.createTempDirectory("audit").resolve("audit.jsonl")
        val sink = JsonlAuditSink(file)
        sink.record(entry(1))
        sink.record(entry(2))
        val lines = Files.readAllLines(file)
        lines.size shouldBe 2
        lines.map { Json.decodeFromString<AuditEntry>(it) } shouldBe listOf(entry(1), entry(2))
    }

    test("each line carries every field, including defaults and a null tenant") {
        val file = Files.createTempDirectory("audit").resolve("audit.jsonl")
        JsonlAuditSink(file).record(entry())
        val line = Files.readAllLines(file).single()
        line shouldContain "\"v\":1"
        line shouldContain "\"tenant\":null"
        for (f in listOf("ts", "actor", "session", "tool", "risk", "args", "decision", "rule", "outcome", "result", "durationMs")) {
            line shouldContain "\"$f\":"
        }
    }

    test("a throwing onWarning does not escape record") {
        val sink = JsonlAuditSink(Files.createTempDirectory("audit")) { error("boom") }
        sink.record(entry())
    }

    test("record creates missing parent directories") {
        val file = Files.createTempDirectory("audit").resolve("a/b/audit.jsonl")
        JsonlAuditSink(file).record(entry())
        Files.readAllLines(file).size shouldBe 1
    }

    test("a write failure warns once and does not throw") {
        val dir: Path = Files.createTempDirectory("audit") // the path is itself a directory
        val warnings = mutableListOf<String>()
        val sink = JsonlAuditSink(dir, warnings::add)
        sink.record(entry())
        sink.record(entry())
        warnings.size shouldBe 1
    }

    test("concurrent writers produce whole lines") {
        val file = Files.createTempDirectory("audit").resolve("audit.jsonl")
        val threads = List(2) {
            val sink = JsonlAuditSink(file)
            thread { repeat(200) { i -> sink.record(entry(i)) } }
        }
        threads.forEach { it.join() }
        val lines = Files.readAllLines(file)
        lines.size shouldBe 400
        lines.forEach { Json.decodeFromString<AuditEntry>(it) }
    }
})
