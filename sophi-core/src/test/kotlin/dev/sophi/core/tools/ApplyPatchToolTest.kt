package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun args(patch: String) = JsonObject(mapOf("patch" to JsonPrimitive(patch))).toString()
private fun envelope(vararg body: String) = (listOf("*** Begin Patch") + body + "*** End Patch").joinToString("\n")

class ApplyPatchToolTest : FunSpec({
    class Fx {
        val root: Path = tempdir().toPath().toRealPath()
        val trash: Path = tempdir().toPath()
        val tool = ApplyPatchTool(root, trash)
        fun file(name: String, text: String) = root.resolve(name).also { Files.createDirectories(it.parent); it.writeText(text) }
        fun run(vararg body: String) = runBlocking { tool.execute(args(envelope(*body))) }
    }

    test("adds a file") {
        val f = Fx()
        f.run("*** Add File: a/new.txt", "+x", "+y") shouldContain "A a/new.txt"
        f.root.resolve("a/new.txt").readText() shouldBe "x\ny\n"
    }

    test("updates with exact context") {
        val f = Fx()
        val p = f.file("f.txt", "1\n2\n3\n")
        f.run("*** Update File: f.txt", "@@", " 1", "-2", "+two", " 3") shouldContain "M f.txt (+1 -1)"
        p.readText() shouldBe "1\ntwo\n3\n"
    }

    test("fuzz: trailing whitespace") {
        val f = Fx()
        val p = f.file("f.txt", "1\n2   \n3\n")
        f.run("*** Update File: f.txt", "@@", " 1", "-2", "+two")
        p.readText() shouldBe "1\ntwo\n3\n"
    }

    test("fuzz: indentation is re-applied") {
        val f = Fx()
        val p = f.file("f.kt", "fun a() {\n    val x = 1\n}\n")
        f.run("*** Update File: f.kt", "@@", "-val x = 1", "+val x = 2")
        p.readText() shouldBe "fun a() {\n    val x = 2\n}\n"
    }

    test("@@ header narrows the search") {
        val f = Fx()
        val p = f.file("f.kt", "fun a() {\n    foo\n}\nfun b() {\n    foo\n}\n")
        f.run("*** Update File: f.kt", "@@ fun b() {", "-    foo", "+    bar")
        p.readText() shouldBe "fun a() {\n    foo\n}\nfun b() {\n    bar\n}\n"
    }

    test("End of File anchors at the end") {
        val f = Fx()
        val p = f.file("f.txt", "x\n2\n3\n4\nx\n")
        f.run("*** Update File: f.txt", "@@", "-x", "+y", "*** End of File")
        p.readText() shouldBe "x\n2\n3\n4\ny\n"
    }

    test("moves and updates") {
        val f = Fx()
        f.file("a.txt", "old\n")
        f.run("*** Update File: a.txt", "*** Move to: b/a.txt", "@@", "-old", "+new") shouldContain "R a.txt → b/a.txt"
        f.root.resolve("a.txt").exists() shouldBe false
        f.root.resolve("b/a.txt").readText() shouldBe "new\n"
    }

    test("deletes a file") {
        val f = Fx()
        f.file("d.txt", "bye\n")
        f.run("*** Delete File: d.txt") shouldContain "D d.txt"
        f.root.resolve("d.txt").exists() shouldBe false
    }

    test("deletes go to the trash") {
        val f = Fx()
        f.file("d.txt", "bye\n")
        f.run("*** Delete File: d.txt")
        Files.walk(f.trash).use { s -> s.anyMatch { it.fileName.toString() == "d.txt" } } shouldBe true
    }

    test("failure in a later file writes nothing") {
        val f = Fx()
        val a = f.file("a.txt", "1\n")
        val b = f.file("b.txt", "2\n")
        val out = f.run("*** Update File: a.txt", "@@", "-1", "+one", "*** Update File: b.txt", "@@", "-nope", "+x", "*** Add File: c.txt", "+c")
        out shouldStartWith "Error:"
        out shouldContain "b.txt"
        out shouldContain "hunk 1"
        a.readText() shouldBe "1\n"
        b.readText() shouldBe "2\n"
        f.root.resolve("c.txt").exists() shouldBe false
    }

    test("add on existing / update missing / outside root") {
        val f = Fx()
        f.file("e.txt", "e\n")
        f.run("*** Add File: e.txt", "+x") shouldStartWith "Error:"
        f.run("*** Update File: missing.txt", "@@", "-a", "+b") shouldStartWith "Error:"
        f.run("*** Add File: ../escape.txt", "+x") shouldStartWith "Error:"
        f.root.resolve("e.txt").readText() shouldBe "e\n"
        f.root.parent.resolve("escape.txt").exists() shouldBe false
    }

    test("preserves CRLF and missing trailing newline") {
        val f = Fx()
        val p = f.file("w.txt", "a\r\nb")
        f.run("*** Update File: w.txt", "@@", " a", "-b", "+c")
        p.readText() shouldBe "a\r\nc"
    }

    test("missing Begin/End Patch is an error") {
        val f = Fx()
        val out = runBlocking { f.tool.execute(args("*** Add File: x.txt\n+x")) }
        out shouldStartWith "Error:"
        out shouldContain "*** Begin Patch"
    }

    test("preview lists every file") {
        val f = Fx()
        f.file("u.txt", "1\n")
        val preview = f.tool.confirmationPreview(args(envelope(
            "*** Add File: n.txt", "+n", "*** Delete File: gone.txt", "*** Update File: u.txt", "@@", "-1", "+one"
        )))!!
        preview shouldContain "A n.txt"
        preview shouldContain "D gone.txt"
        preview shouldContain "-1"
        preview shouldContain "+one"
    }

    test("permission subjects cover moves") {
        val f = Fx()
        f.file("a.txt", "1\n")
        val subjects = f.tool.permissionSubjects(args(envelope("*** Update File: a.txt", "*** Move to: b.txt", "@@", "-1", "+2")))!!
        subjects.map { it.text }.toSet() shouldBe setOf("a.txt", "b.txt")
    }

    test("deleting a directory or the root is refused before anything is written") {
        val f = Fx()
        val a = f.file("a.txt", "1\n")
        Files.createDirectories(f.root.resolve("dir"))
        listOf("dir", ".").forEach { target ->
            val out = f.run("*** Update File: a.txt", "@@", "-1", "+one", "*** Delete File: $target")
            out shouldStartWith "Error:"
            a.readText() shouldBe "1\n"
        }
        f.root.resolve("dir").exists() shouldBe true
    }
})
