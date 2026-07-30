package dev.sophi.core.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class EditToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: EditTool

    beforeTest {
        root = createTempDirectory("sophi-edit-test")
        tool = EditTool(root)
    }

    test("execute() replaces a unique occurrence") {
        root.resolve("a.txt").writeText("hello world")
        val result = runBlocking {
            tool.execute("""{"path":"a.txt","old_string":"world","new_string":"there"}""")
        }
        root.resolve("a.txt").readText() shouldBe "hello there"
        result shouldBe "Replaced 1 occurrence in a.txt"
    }

    test("execute() returns an error when old_string is not found") {
        root.resolve("a.txt").writeText("hello world")
        val result = runBlocking {
            tool.execute("""{"path":"a.txt","old_string":"missing","new_string":"x"}""")
        }
        result shouldBe "Error: old_string not found in a.txt"
        root.resolve("a.txt").readText() shouldBe "hello world"
    }

    test("execute() returns an error when old_string occurs multiple times without replace_all") {
        root.resolve("a.txt").writeText("foo foo foo")
        val result = runBlocking {
            tool.execute("""{"path":"a.txt","old_string":"foo","new_string":"bar"}""")
        }
        result shouldBe "Error: old_string found 3 times in a.txt; add more surrounding context or set replace_all to true"
        root.resolve("a.txt").readText() shouldBe "foo foo foo"
    }

    test("execute() replaces every occurrence when replace_all is true") {
        root.resolve("a.txt").writeText("foo foo foo")
        val result = runBlocking {
            tool.execute("""{"path":"a.txt","old_string":"foo","new_string":"bar","replace_all":true}""")
        }
        root.resolve("a.txt").readText() shouldBe "bar bar bar"
        result shouldBe "Replaced 3 occurrence(s) in a.txt"
    }

    test("execute() throws FileNotFoundException for a missing file") {
        shouldThrow<FileNotFoundException> {
            runBlocking { tool.execute("""{"path":"missing.txt","old_string":"a","new_string":"b"}""") }
        }
    }

    test("execute() throws IllegalArgumentException when path escapes root") {
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"../outside.txt","old_string":"a","new_string":"b"}""") }
        }
    }

    test("name is edit_file") {
        tool.name shouldBe "edit_file"
    }

    test("riskLevel is DESTRUCTIVE") {
        tool.riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("ruleVerdict is LOW_RISK for a path under scratch/") {
        tool.ruleVerdict("""{"path":"scratch/a.txt","old_string":"x","new_string":"y"}""") shouldBe RuleVerdict.LOW_RISK
    }

    test("ruleVerdict is HIGH_RISK for a path containing credentials") {
        tool.ruleVerdict("""{"path":"secrets/credentials.json","old_string":"x","new_string":"y"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is HIGH_RISK for a path that escapes the working directory") {
        tool.ruleVerdict("""{"path":"../outside.txt","old_string":"x","new_string":"y"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is UNKNOWN for an ordinary in-project path") {
        tool.ruleVerdict("""{"path":"src/Main.kt","old_string":"x","new_string":"y"}""") shouldBe RuleVerdict.UNKNOWN
    }
})
