package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.LocalServerKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress

private class Holder : CliktCommand() {
    val provider by ProviderOptions()
    override fun run() = Unit
}

private fun options(vararg args: String): ProviderOptions = Holder().apply { parse(args.toList()) }.provider

private const val NO_SERVER_URL = "http://127.0.0.1:1/v1"

class ProviderOptionsTest : FunSpec({
    test("local with nothing running fails with the how-to message") {
        val e = shouldThrow<UsageError> { options().resolve { null } }
        e.message shouldContain "--provider claude"
    }

    test("local picks the server's first model and the detected window") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/props") { ex ->
            val body = """{"n_ctx":32768}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val r = options().resolve { LocalServer(LocalServerKind.LLAMA_CPP, url, listOf("qwen3-8b", "gemma")) }
            r.type shouldBe "local"
            r.baseUrl shouldBe url
            r.model shouldBe "qwen3-8b"
            r.contextWindowTokens shouldBe 32768
            r.warnings.shouldBeEmpty()
        } finally { server.stop(0) }
    }

    test("explicit --model and --context-window-tokens win") {
        val r = options("--model", "gemma", "--context-window-tokens", "16000")
            .resolve { LocalServer(LocalServerKind.LLAMA_CPP, NO_SERVER_URL, listOf("qwen3-8b", "gemma")) }
        r.model shouldBe "gemma"
        r.contextWindowTokens shouldBe 16000
    }

    test("an unreadable window falls back to 8192 with a warning") {
        val r = options().resolve { LocalServer(LocalServerKind.LLAMA_CPP, NO_SERVER_URL, listOf("qwen3-8b")) }
        r.contextWindowTokens shouldBe 8192
        r.warnings shouldBe listOf("Couldn't read the server's context window; assuming 8192. Pass --context-window-tokens to set it.")
    }

    test("the local-server error suggests --provider claude --model <name>, never a specific Claude model") {
        val e = shouldThrow<UsageError> { options().resolve { null } }
        e.message shouldContain "--provider claude --model <name>"
        e.message shouldNotContain "claude-"
    }

    test("local with an empty model list fails clearly") {
        val e = shouldThrow<UsageError> { options().resolve { LocalServer(null, NO_SERVER_URL, emptyList()) } }
        e.message shouldBe "The local server at $NO_SERVER_URL has no model loaded. Load one, or pass --model."
    }

    test("claude without --model fails") {
        val e = shouldThrow<UsageError> { options("--provider", "claude").resolve { error("no probe for claude") } }
        e.message shouldContain "--model is required"
    }

    test("claude with --model uses it and defaults the window to 200000") {
        val r = options("--provider", "claude", "--model", "some-claude").resolve { error("no probe for claude") }
        r.type shouldBe "claude"
        r.model shouldBe "some-claude"
        r.contextWindowTokens shouldBe 200_000
    }

    test("openai-compat without --model fails") {
        val e = shouldThrow<UsageError> {
            options("--provider", "openai-compat", "--base-url", "http://x/v1").resolve { error("no probe") }
        }
        e.message shouldContain "--model is required with --provider openai-compat"
    }

    test("openai-compat defaults the window to 8192") {
        val r = options("--provider", "openai-compat", "--base-url", "http://x/v1", "--model", "m").resolve { error("no probe") }
        r.baseUrl shouldBe "http://x/v1"
        r.contextWindowTokens shouldBe 8192
    }

    test("local finds nomic-embed for memory") {
        val r = options("--context-window-tokens", "8192").resolve {
            LocalServer(LocalServerKind.LM_STUDIO, NO_SERVER_URL, listOf("qwen3-8b", "text-embedding-nomic-embed-text-v1.5"))
        }
        r.model shouldBe "qwen3-8b"
        r.embeddingModel shouldBe "text-embedding-nomic-embed-text-v1.5"
    }

    test("local never picks an embedding model as the chat model") {
        val r = options("--context-window-tokens", "8192").resolve {
            LocalServer(LocalServerKind.LM_STUDIO, NO_SERVER_URL, listOf("text-embedding-nomic-embed-text-v1.5", "qwen3-8b"))
        }
        r.model shouldBe "qwen3-8b"
    }

    test("every provider-using command shares the group") {
        listOf(
            SophiCli(), EvalsRunCommand(), GoalRunCommand(), ProposalImplementCommand(), ScheduleDaemonCommand(),
            ScheduleRunDueCommand(), SkillVerifyCommand(), TournamentRunCommand()
        ).forEach { cmd ->
            shouldThrow<PrintHelpMessage> { cmd.parse(listOf("--help")) }
            val help = cmd.getFormattedHelp()!!.replace(Regex("\\s+"), " ")
            help shouldContain "Model provider"
            help shouldContain "local (default: finds llama.cpp, LM Studio or Ollama), openai-compat, or claude"
        }
    }
})
