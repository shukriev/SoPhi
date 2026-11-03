package dev.sophi.ai.providers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain as shouldContainItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun pinned(name: String, bytes: ByteArray, shaOverride: String? = null) =
    PinnedFile("https://example.test/$name", name, shaOverride ?: sha(bytes), bytes.size.toLong())

/** A downloader serving [content] by URL; records each call. */
private class FakeDownload(val content: Map<String, ByteArray>) : (String, Path, (Long, Long) -> Unit) -> Unit {
    val urls = mutableListOf<String>()
    override fun invoke(url: String, dest: Path, onProgress: (Long, Long) -> Unit) {
        urls += url
        Files.createDirectories(dest.parent)
        dest.writeBytes(content.getValue(url))
    }
}

private fun engineTarball(): ByteArray {
    val src = createTempDirectory("fake-engine-src")
    Files.createDirectories(src.resolve("build/bin"))
    src.resolve("build/bin/llama-server").apply { writeText("#!/bin/sh\nexit 0\n"); toFile().setExecutable(true) }
    val tar = createTempDirectory("fake-engine-tar").resolve("e.tar.gz")
    check(ProcessBuilder("tar", "czf", tar.toString(), "-C", src.toString(), "build").start().waitFor() == 0)
    return Files.readAllBytes(tar)
}

class LocalModelInstallerTest : FunSpec({
    test("engine asset per OS and arch; Windows and unknown platforms are refused") {
        llamaCppEngineFor("Mac OS X", "aarch64")!!.fileName shouldBe "llama-$LLAMA_CPP_TAG-bin-macos-arm64.tar.gz"
        llamaCppEngineFor("Linux", "amd64")!!.fileName shouldBe "llama-$LLAMA_CPP_TAG-bin-ubuntu-x64.tar.gz"
        llamaCppEngineFor("Linux", "x86_64")!!.fileName shouldBe "llama-$LLAMA_CPP_TAG-bin-ubuntu-x64.tar.gz"
        llamaCppEngineFor("Linux", "aarch64")!!.fileName shouldBe "llama-$LLAMA_CPP_TAG-bin-ubuntu-arm64.tar.gz"
        llamaCppEngineFor("Linux", "aarch64")!!.url shouldBe
            "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_CPP_TAG/llama-$LLAMA_CPP_TAG-bin-ubuntu-arm64.tar.gz"
        llamaCppEngineFor("Windows 11", "amd64") shouldBe null
        llamaCppEngineFor("Mac OS X", "x86_64") shouldBe null
        unsupportedPlatformMessage("Windows 11", "amd64") shouldContain "Windows isn't supported yet"
        unsupportedPlatformMessage("Mac OS X", "x86_64") shouldContain "Apple Silicon"
    }

    test("installs engine and model, then a second run downloads nothing") {
        val engineBytes = engineTarball()
        val modelBytes = "gguf bytes".toByteArray()
        val engine = pinned("engine.tar.gz", engineBytes)
        val model = pinned("m.gguf", modelBytes)
        val download = FakeDownload(mapOf(engine.url to engineBytes, model.url to modelBytes))
        val dir = createTempDirectory("lm")
        val installer = LocalModelInstaller(engine, model, dir, download)

        installer.isInstalled() shouldBe false
        installer.install()
        installer.isInstalled() shouldBe true
        Files.isExecutable(installer.serverBinary) shouldBe true
        Files.readAllBytes(installer.modelFile).toList() shouldBe modelBytes.toList()
        installer.serverCommand().take(3) shouldBe listOf(installer.serverBinary.toString(), "-m", installer.modelFile.toString())
        installer.serverCommand() shouldContainItem "8080" // where `local` discovery looks for llama.cpp

        LocalModelInstaller(engine, model, dir, download).install()
        download.urls shouldBe listOf(engine.url, model.url)
    }

    test("a checksum mismatch fails and deletes the partial file") {
        val engineBytes = engineTarball()
        val engine = pinned("engine.tar.gz", engineBytes)
        val model = pinned("m.gguf", "gguf".toByteArray(), shaOverride = "0".repeat(64))
        val dir = createTempDirectory("lm")
        val installer = LocalModelInstaller(engine, model, dir, FakeDownload(mapOf(engine.url to engineBytes, model.url to "gguf".toByteArray())))

        shouldThrow<java.io.IOException> { installer.install() }.message!! shouldContain "Checksum mismatch for m.gguf"
        dir.resolve("partial/m.gguf").exists() shouldBe false
        installer.modelFile.exists() shouldBe false
    }

    test("a partial file already at full size is only checked, not downloaded again") {
        val engineBytes = engineTarball()
        val modelBytes = "gguf bytes".toByteArray()
        val engine = pinned("engine.tar.gz", engineBytes)
        val model = pinned("m.gguf", modelBytes)
        val dir = createTempDirectory("lm")
        Files.createDirectories(dir.resolve("partial"))
        dir.resolve("partial/m.gguf").writeBytes(modelBytes)
        val download = FakeDownload(mapOf(engine.url to engineBytes))
        LocalModelInstaller(engine, model, dir, download).install()
        download.urls shouldBe listOf(engine.url)
    }
})
