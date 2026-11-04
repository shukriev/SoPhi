package dev.sophi.core.tools

import dev.sophi.core.agent.ToolCallScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class CodeModeToolTest : FunSpec({
    fun args(code: String) = """{"code":${JsonPrimitive(code)}}"""

    /** Runs [code] with a bridge that records each call and answers with [answer]. */
    suspend fun run(
        code: String, tool: CodeModeTool = CodeModeTool(),
        answer: suspend (String, String) -> String = { n, a -> "$n:$a" },
    ): Pair<String, List<Pair<String, String>>> {
        val seen = mutableListOf<Pair<String, String>>()
        val result = withContext(ToolCallScope { n, a -> seen += n to a; answer(n, a) }) { tool.execute(args(code)) }
        return result to seen
    }

    test("tools.<name>(args) goes through the scope with the args as JSON, and return gives the result") {
        val (result, seen) = run("return tools.echo({a: 1})")
        seen shouldBe listOf("echo" to """{"a":1}""")
        result shouldBe """echo:{"a":1}"""
    }

    test("a value that isn't a string comes back as JSON; no return says so") {
        run("return {x: [1, 2]}").first shouldBe """{"x":[1,2]}"""
        run("var a = 1").first shouldBe "(the script returned nothing)"
    }

    test("a non-object argument is sent as its JSON, a missing one as {}") {
        run("tools.x('a'); tools.y()").second shouldBe listOf("x" to "\"a\"", "y" to "{}")
    }

    test("the script reaches no Java, files or modules") {
        run("return typeof java + typeof Packages + typeof JavaAdapter + typeof load + typeof require + typeof print")
            .first shouldBe "undefined".repeat(6)
        run("return java.io.File").first shouldStartWith "Error: the script failed"
        run("return tools.echo.getClass()").first shouldStartWith "Error: the script failed"
        run("return (function(){}).constructor('return typeof Packages')()").first shouldBe "undefined"
    }

    test("a script that runs too long is stopped") {
        val (result, _) = run("while (true) {}", CodeModeTool(timeLimitMillis = 300))
        result shouldStartWith "Error: the script failed"
        result shouldContain "time limit"
    }

    test("time spent inside tool calls doesn't count against the time limit") {
        val (result, seen) = run("tools.a(); tools.b(); for (var i = 0; i < 200000; i++) {} return 'done'", CodeModeTool(timeLimitMillis = 300)) { _, _ -> delay(400); "ok" }
        result shouldBe "done"
        seen.size shouldBe 2
    }

    test("a tool error throws in the script, which can catch it") {
        run("try { tools.x({}) } catch (e) { return 'caught ' + e.message }") { _, _ -> "Error: denied" }
            .first shouldBe "caught Error: denied"
    }

    test("an uncaught failure says which calls already ran") {
        val (result, _) = run("tools.read({}); tools.write({})") { n, _ -> if (n == "write") "Error: denied" else "ok" }
        result shouldStartWith "Error: the script failed: Error: denied"
        result shouldContain "read, write"
    }

    test("a script may make at most maxCalls tool calls") {
        val (result, seen) = run("tools.a(); tools.a(); tools.a()", CodeModeTool(maxCalls = 2))
        seen.size shouldBe 2
        result shouldContain "at most 2 tool calls"
    }

    test("a script can't call code_mode") {
        val (result, seen) = run("return tools.code_mode({code: '1'})")
        seen shouldBe emptyList()
        result shouldStartWith "Error: the script failed"
    }

    test("without a ToolCallScope the tool refuses to run") {
        CodeModeTool().execute(args("return 1")) shouldStartWith "Error: "
    }

    test("a syntax error is reported, not thrown") {
        run("return (").first shouldStartWith "Error: the script failed"
    }

    test("stopping the turn stops the script") {
        withTimeout(5_000) {
            val job = async { run("while (true) {}", CodeModeTool(timeLimitMillis = 60_000)) }
            delay(200)
            job.cancel()
            runCatching { job.await() }
        }
    }

    test("parameters are a valid JSON schema with code required") {
        Json.parseToJsonElement(CodeModeTool().parametersJson).toString() shouldContain "\"required\":[\"code\"]"
    }
})
