package dev.sophi.cli

import dev.sophi.ai.providers.LocalModelInstaller
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.LocalServerKind
import dev.sophi.ai.providers.PinnedFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SetupLocalCommandTest : FunSpec({
    val engine = PinnedFile("https://example.test/engine.tar.gz", "engine.tar.gz", "0".repeat(64), 12_000_000L)
    val model = PinnedFile("https://example.test/m.gguf", "m.gguf", "0".repeat(64), 5_000_000_000L)

    fun run(
        discovered: LocalServer? = null,
        engineFile: PinnedFile? = engine,
        answer: Boolean = false,
        force: Boolean = false,
    ): Pair<String, List<String>> {
        val out = StringBuilder()
        val downloads = mutableListOf<String>()
        val dir = tempdir().toPath()
        SetupLocal(
            discover = { discovered },
            engine = engineFile,
            installer = { e -> LocalModelInstaller(e, model, dir, download = { url, _, _ -> downloads += url; error("offline") }) },
            confirm = { answer },
            echo = { out.appendLine(it) },
            force = force,
        ).run()
        return out.toString() to downloads
    }

    test("an already-running local server stops setup before anything is offered") {
        val (out, downloads) = run(discovered = LocalServer(LocalServerKind.OLLAMA, "http://127.0.0.1:11434/v1", listOf("qwen3:8b")), answer = true)
        out shouldContain "Ollama"
        out shouldContain "--force"
        out shouldNotContain "Download"
        downloads shouldBe emptyList()
    }

    test("--force ignores the running server") {
        val (out, _) = run(discovered = LocalServer(LocalServerKind.OLLAMA, "http://127.0.0.1:11434/v1", listOf("qwen3:8b")), force = true)
        out shouldContain "engine.tar.gz"
    }

    test("lists what it downloads with sizes and downloads nothing on no") {
        val (out, downloads) = run(answer = false)
        out shouldContain "engine.tar.gz (11 MB)"
        out shouldContain "m.gguf (4.7 GB)"
        out shouldContain "Nothing downloaded"
        downloads shouldBe emptyList()
    }

    test("a failed download is reported, with a hint that rerunning resumes") {
        val (out, downloads) = run(answer = true)
        downloads shouldBe listOf(engine.url)
        out shouldContain "Couldn't download engine.tar.gz"
        out shouldContain "resume"
    }

    test("an unsupported platform gets a clear message") {
        val (out, downloads) = run(engineFile = null, answer = true)
        out shouldContain "Install Ollama or LM Studio"
        downloads shouldBe emptyList()
    }
})
