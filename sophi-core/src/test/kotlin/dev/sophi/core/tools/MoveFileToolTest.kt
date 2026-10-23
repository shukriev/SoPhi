package dev.sophi.core.tools

import dev.sophi.core.undo.FileMove
import dev.sophi.core.undo.RoundChanges
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MoveFileToolTest : FunSpec({
    lateinit var root: Path
    lateinit var home: Path
    lateinit var trash: Path
    lateinit var move: MoveFileTool
    lateinit var delete: DeleteFileTool

    beforeTest {
        root = createTempDirectory("sophi-move-root").toAbsolutePath().normalize()
        home = createTempDirectory("sophi-move-home").toAbsolutePath().normalize()
        trash = createTempDirectory("sophi-move-trash").toAbsolutePath().normalize()
        move = MoveFileTool(root, home)
        delete = DeleteFileTool(root, trash, home)
    }

    // Either an "Error..." result or a thrown exception counts as a refusal.
    suspend fun refused(block: suspend () -> String): Boolean =
        runCatching { block() }.fold({ it.startsWith("Error") }, { true })

    test("move_file moves a file and records the move") {
        runBlocking {
            root.resolve("a.txt").writeText("hi")
            val changes = RoundChanges()
            val result = withContext(changes) { move.execute("""{"from":"a.txt","to":"sub/b.txt"}""") }
            result shouldBe "Moved ${root.resolve("a.txt")} to ${root.resolve("sub/b.txt")}"
            changes.moves shouldBe listOf(FileMove(root.resolve("a.txt").toString(), root.resolve("sub/b.txt").toString()))
            root.resolve("a.txt").exists() shouldBe false
            root.resolve("sub/b.txt").readText() shouldBe "hi"
        }
    }

    test("move_file refuses to overwrite") {
        runBlocking {
            root.resolve("a.txt").writeText("A")
            root.resolve("b.txt").writeText("B")
            refused { move.execute("""{"from":"a.txt","to":"b.txt"}""") } shouldBe true
            root.resolve("a.txt").readText() shouldBe "A"
            root.resolve("b.txt").readText() shouldBe "B"
        }
    }

    test("move_file expands ~ and accepts paths outside the root") {
        runBlocking {
            home.resolve("x.txt").writeText("x")
            move.execute("""{"from":"~/x.txt","to":"~/y/x.txt"}""")
            home.resolve("x.txt").exists() shouldBe false
            home.resolve("y/x.txt").readText() shouldBe "x"
        }
    }

    test("delete_file moves the file into the trash and records it") {
        runBlocking {
            root.resolve("d.txt").writeText("gone")
            val changes = RoundChanges()
            val result = withContext(changes) { delete.execute("""{"path":"d.txt"}""") }
            result shouldStartWith "Moved"
            root.resolve("d.txt").exists() shouldBe false
            val moved = changes.moves.single()
            moved.to shouldStartWith trash.toString()
            Path.of(moved.to).readText() shouldBe "gone"
        }
    }

    test("delete_file on a missing path is an error and records nothing") {
        runBlocking {
            val changes = RoundChanges()
            withContext(changes) { refused { delete.execute("""{"path":"nope.txt"}""") } } shouldBe true
            changes.moves shouldBe emptyList()
        }
    }

    test("a nested RoundChanges records on its parent too") {
        val outer = RoundChanges()
        val inner = RoundChanges(outer)
        val m = FileMove("/a", "/b")
        inner.record(m)
        inner.moves shouldBe listOf(m)
        outer.moves shouldBe listOf(m)
    }

    test("ruleVerdict is HIGH_RISK for a path outside the root") {
        delete.ruleVerdict("""{"path":"/etc/hosts"}""") shouldBe RuleVerdict.HIGH_RISK
        move.ruleVerdict("""{"from":"a.txt","to":"/etc/a"}""") shouldBe RuleVerdict.HIGH_RISK
    }
})
