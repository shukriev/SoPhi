package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import dev.sophi.ai.providers.NO_LOCAL_SERVER_MESSAGE
import dev.sophi.sdk.DoctorCheck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private val OK_MODEL: (String, String?, String?, Int?) -> ResolvedProvider =
    { t, _, _, _ -> ResolvedProvider(t, "http://127.0.0.1:8080/v1", "qwen3-8b", 32768, null, emptyList()) }

private fun run(
    settings: Path,
    resolve: (String, String?, String?, Int?) -> ResolvedProvider = OK_MODEL,
    others: List<DoctorCheck> = emptyList(),
): Pair<Int, List<String>> {
    val out = mutableListOf<String>()
    val code = Doctor(settings, resolve, { others }, { out += it }).run()
    return code to out
}

private fun tempFile(): Path = Files.createTempDirectory("doctor").resolve("settings.json")

class DoctorCommandTest : FunSpec({
    test("broken settings JSON is a failure with a fix") {
        val file = tempFile().also { it.writeText("{ not json") }
        val (code, out) = run(file)
        code shouldBe 1
        out.joinToString("\n") shouldContain "isn't valid JSON"
        out.single { it.trim().startsWith("fix:") } shouldContain "edit or delete $file"
    }

    test("a model that doesn't resolve is a failure whose fix is sophi setup local") {
        val (code, out) = run(tempFile(), resolve = { _, _, _, _ -> throw UsageError(NO_LOCAL_SERVER_MESSAGE) })
        code shouldBe 1
        out.single { it.trim().startsWith("fix:") } shouldContain "sophi setup local"
    }

    test("the saved provider settings reach resolve") {
        val file = tempFile().also { it.writeText("""{"provider":"openai-compat","base-url":"http://box:1/v1","model":"m","context-window-tokens":"4096"}""") }
        var got: List<Any?> = emptyList()
        val (code, _) = run(file, resolve = { t, u, m, w -> got = listOf(t, u, m, w); OK_MODEL(t, u, m, w) })
        code shouldBe 0
        got shouldBe listOf("openai-compat", "http://box:1/v1", "m", 4096)
    }

    test("all ok: exit 0 and no fix lines") {
        val (code, out) = run(tempFile(), others = listOf(DoctorCheck("skills", true, "fine")))
        code shouldBe 0
        out.filter { it.trim().startsWith("fix:") }.shouldBeEmpty()
        out.joinToString("\n") shouldContain "qwen3-8b"
        out.joinToString("\n") shouldContain "no settings saved"
    }

    test("any failing check: exit 1 and its fix line") {
        val (code, out) = run(tempFile(), others = listOf(DoctorCheck("docker", false, "`docker info` failed", "start Docker Desktop")))
        code shouldBe 1
        out.single { it.trim().startsWith("fix:") } shouldContain "start Docker Desktop"
    }
})
