package dev.sophi.gateway.discord

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AttachmentsTest : FunSpec({
    test("cdn hosts only") {
        allowedAttachmentUrl("https://cdn.discordapp.com/a.ogg").shouldBeTrue()
        allowedAttachmentUrl("https://media.discordapp.net/a.png").shouldBeTrue()
        allowedAttachmentUrl("http://cdn.discordapp.com/a.ogg").shouldBeFalse()
        allowedAttachmentUrl("https://evil.example/a.ogg").shouldBeFalse()
        allowedAttachmentUrl("https://cdn.discordapp.com.evil.example/a").shouldBeFalse()
        allowedAttachmentUrl("https://user@cdn.discordapp.com:8443/a").shouldBeFalse()
        allowedAttachmentUrl("not a url").shouldBeFalse()
    }

    test("download stops past the cap") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/big") { ex -> ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(ByteArray(2048)) } }
        server.createContext("/small") { ex -> ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(ByteArray(512)) } }
        server.start()
        try {
            val dir = createTempDirectory("dl")
            val base = "http://127.0.0.1:${server.address.port}"
            val http = HttpClient.newHttpClient()
            runBlocking {
                val big = dir.resolve("big.bin")
                download("$base/big", 1024, big, http, allowed = { true }).exceptionOrNull()!!.message!! shouldContain "too large"
                Files.exists(big).shouldBeFalse()
                Files.size(download("$base/small", 1024, dir.resolve("small.bin"), http, allowed = { true }).getOrThrow()) shouldBe 512L
                download("$base/small", 1024, dir.resolve("x.bin"), http).isFailure.shouldBeTrue()   // not a CDN host
            }
        } finally { server.stop(0) }
    }
})
