package dev.sophi.core.tools

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.permissions.Effect
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.mockk
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class LlmGuardianTest : FunSpec({
    fun replying(text: String): LLMProvider = mockk<LLMProvider>().also {
        coEvery { it.complete(any()) } returns LLMResponse.Text(text, TokenUsage(1, 1))
    }
    suspend fun LlmGuardian.go() = review("bash", "runs a command", """{"command":"rm -rf build"}""", "clean the build")

    test("DENY with a reason") {
        LlmGuardian(replying("DENY: wipes the repo"), "m").go() shouldBe GuardianVerdict(Effect.DENY, "wipes the repo")
    }

    test("a lowercase ask inside an ALLOW reason stays ALLOW") {
        LlmGuardian(replying("ALLOW - no need to ask, it is scratch"), "m").go().effect shouldBe Effect.ALLOW
    }

    test("a bare ASK") {
        LlmGuardian(replying("ASK"), "m").go().effect shouldBe Effect.ASK
    }

    test("no verdict word is ASK") {
        val v = LlmGuardian(replying("I am not sure"), "m").go()
        v.effect shouldBe Effect.ASK
        v.reason shouldContain "no verdict"
    }

    test("a failing provider is ASK") {
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } throws RuntimeException("connection refused")
        val v = LlmGuardian(p, "m").go()
        v.effect shouldBe Effect.ASK
        v.reason shouldStartWith "guardian unavailable"
    }

    test("a timeout is ASK") {
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } coAnswers { delay(5_000); LLMResponse.Text("ALLOW", TokenUsage(1, 1)) }
        LlmGuardian(p, "m", timeout = 50.milliseconds).go().effect shouldBe Effect.ASK
    }

    test("the request names the model, is deterministic, is no System 1 judgment, and carries the call") {
        var seen: CompletionRequest? = null
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } answers { seen = firstArg(); LLMResponse.Text("ALLOW", TokenUsage(1, 1)) }
        LlmGuardian(p, "m").go()
        val r = seen!!
        r.model shouldBe "m"
        r.temperature shouldBe 0.0
        r.judgment shouldBe null
        val user = r.messages.single { it.role == MessageRole.USER }.content
        user shouldContain "clean the build"
        user shouldContain "bash"
        user shouldContain "rm -rf build"
    }

    // What the guardian is shown for a bash command run in [root].
    suspend fun shown(root: Path, command: String): String {
        var seen: CompletionRequest? = null
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } answers { seen = firstArg(); LLMResponse.Text("ALLOW", TokenUsage(1, 1)) }
        val args = buildJsonObject { put("command", JsonPrimitive(command)) }.toString()
        LlmGuardian(p, "m", workspace = root).review("bash", "runs a command", args, "do it")
        return seen!!.messages.single { it.role == MessageRole.USER }.content
    }

    test("a script the command runs is shown with its contents, however it is run") {
        val root = Files.createTempDirectory("guardian")
        Files.writeString(root.resolve("x.sh"), "rm -rf ~/secret-marker\n")
        Files.createDirectories(root.resolve("tools"))
        Files.writeString(root.resolve("tools/y.py"), "import shutil; shutil.rmtree('py-marker')\n")
        for (cmd in listOf("bash x.sh", "sh -x x.sh", "./x.sh", "source x.sh", ". ./x.sh", "make && bash x.sh", "FOO=1 /bin/zsh x.sh")) {
            withClue(cmd) { shown(root, cmd) shouldContain "secret-marker" }
        }
        shown(root, "python3 tools/y.py --fast") shouldContain "py-marker"
        shown(root, "make; node \"tools/y.py\"") shouldContain "py-marker"
    }

    test("inline code and plain commands read no file") {
        val root = Files.createTempDirectory("guardian")
        Files.writeString(root.resolve("x.sh"), "secret-marker")
        shown(root, "bash -c 'cat x.sh'") shouldNotContain "secret-marker"
        shown(root, "git status") shouldNotContain "Scripts"
    }

    test("a script outside the workspace, missing or binary is named, not read") {
        val root = Files.createTempDirectory("guardian")
        val outside = Files.createTempDirectory("elsewhere").resolve("o.sh").also { Files.writeString(it, "outside-marker") }
        Files.write(root.resolve("b.sh"), byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0, 0, 1))
        val shownOutside = shown(root, "bash $outside")
        shownOutside shouldNotContain "outside-marker"
        shownOutside shouldContain "outside the workspace"
        shown(root, "bash ../o.sh") shouldContain "outside the workspace"
        shown(root, "bash gone.sh") shouldContain "not found"
        shown(root, "./b.sh") shouldContain "binary"
    }

    test("a large script is cut and says so") {
        val root = Files.createTempDirectory("guardian")
        Files.writeString(root.resolve("big.sh"), "a".repeat(100_000) + "tail-marker")
        val user = shown(root, "bash big.sh")
        user shouldNotContain "tail-marker"
        user shouldContain "truncated"
    }
})
