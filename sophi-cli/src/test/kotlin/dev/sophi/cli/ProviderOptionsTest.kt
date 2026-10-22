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

/** Serves each path in [routes] with a 200 and its body; returns the server and its /v1 base URL. */
private fun serving(vararg routes: Pair<String, String>): Pair<HttpServer, String> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    for ((path, body) in routes) server.createContext(path) { ex ->
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server to "http://127.0.0.1:${server.address.port}/v1"
}

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

    test("local prefers the model the server has in memory over the first one it lists") {
        val (server, url) = serving("/api/v0/models" to
            """{"data":[{"id":"big-unloaded","type":"llm","state":"not-loaded"},{"id":"qwen3-8b","type":"llm","state":"loaded","loaded_context_length":16384}]}""")
        try {
            val r = options().resolve { LocalServer(LocalServerKind.LM_STUDIO, url, listOf("big-unloaded", "qwen3-8b")) }
            r.model shouldBe "qwen3-8b"
            r.contextWindowTokens shouldBe 16384
        } finally { server.stop(0) }
    }

    test("local --base-url that doesn't answer names that URL, not the default ports") {
        val e = shouldThrow<UsageError> { options("--base-url", NO_SERVER_URL).resolve { error("no discovery with --base-url") } }
        e.message shouldContain NO_SERVER_URL
        e.message shouldContain "/v1"
        e.message shouldNotContain ":11434"
    }

    test("local --base-url tolerates a trailing slash") {
        val (server, url) = serving("/v1/models" to """{"data":[{"id":"m"}]}""")
        try {
            val r = options("--base-url", "$url/", "--context-window-tokens", "8192").resolve { error("no discovery with --base-url") }
            r.baseUrl shouldBe url
            r.model shouldBe "m"
        } finally { server.stop(0) }
    }

    test("a fixed eval window never exceeds what the server loaded") {
        val r = ResolvedProvider("local", null, "m", 4096, null, emptyList())
        r.windowAtMost(100_000) shouldBe 4096
        r.copy(contextWindowTokens = 200_000).windowAtMost(100_000) shouldBe 100_000
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

    test("--memory uses the local server's nomic-embed model only when no embedding flag was given") {
        val resolved = ResolvedProvider("local", "http://127.0.0.1:1234/v1", "qwen3-8b", 8192, "nomic-embed-text", emptyList())
        chooseEmbedding(true, null, null, null, resolved) shouldBe
            EmbeddingChoice("nomic-embed-text", "http://127.0.0.1:1234/v1", 768)
        // An explicit --embedding-base-url is kept, never swapped for the chat server's URL.
        chooseEmbedding(true, null, "http://emb:11434/v1", null, resolved) shouldBe
            EmbeddingChoice(null, "http://emb:11434/v1", 1536)
        chooseEmbedding(true, "my-embed", null, 512, resolved) shouldBe EmbeddingChoice("my-embed", null, 512)
        chooseEmbedding(false, null, null, null, resolved) shouldBe EmbeddingChoice(null, null, 1536)
    }
})
