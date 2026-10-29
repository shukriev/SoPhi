package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.LocalServerKind
import dev.sophi.sdk.ModelCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

private class CatalogHolder : CliktCommand() {
    val provider by ProviderOptions()
    override fun run() = Unit
}

private fun opts(vararg args: String): ProviderOptions = CatalogHolder().apply { parse(args.toList()) }.provider

private val CATALOG = ModelCatalog.parse(
    """{"anthropic":{"claude-x":{"c":123456,"i":3,"p":15,"t":1}},
        "siliconflow":{"Qwen/Qwen3-8B":{"c":131000,"i":0.1,"p":0.2,"t":1,"k":"alibaba/qwen3-8b"}}}"""
)

class ProviderOptionsCatalogTest : FunSpec({
    test("a cloud model's window comes from the catalog") {
        val r = opts("--provider", "claude", "--model", "claude-x").resolve(catalog = CATALOG)
        r.contextWindowTokens shouldBe 123456
        r.cloud shouldBe true
        r.catalogProvider shouldBe "anthropic"
        r.info!!.inputUsdPerM shouldBe 3.0
    }

    test("a flag beats the catalog") {
        opts("--provider", "claude", "--model", "claude-x", "--context-window-tokens", "5000").resolve(catalog = CATALOG)
            .contextWindowTokens shouldBe 5000
    }

    test("an uncatalogued cloud model keeps the old default") {
        val r = opts("--provider", "openai-compat", "--model", "mystery", "--base-url", "https://api.example.com/v1").resolve(catalog = CATALOG)
        r.contextWindowTokens shouldBe 8192
        r.info shouldBe null
    }

    test("a local window never comes from the catalog") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/props") { ex ->
            val body = """{"n_ctx":32768}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val local = LocalServer(LocalServerKind.LLAMA_CPP, url, listOf("qwen3-8b-q4_k_m"))
            val r = opts().resolve(discover = { local }, catalog = CATALOG)
            r.contextWindowTokens shouldBe 32768
            r.cloud shouldBe false
            r.info!!.tools shouldBe true
            r.info!!.priced shouldBe false
            r.server shouldBe local
        } finally { server.stop(0) }
    }

    test("cloud follows the base URL") {
        for (url in listOf("http://127.0.0.1:1234/v1", "http://192.168.1.5:8080/v1", "http://mini.local:1234/v1", "http://localhost:8080/v1", "http://10.0.0.2/v1")) {
            opts("--provider", "openai-compat", "--model", "m", "--base-url", url).resolve(catalog = CATALOG).cloud shouldBe false
        }
        val r = opts("--provider", "openai-compat", "--model", "m", "--base-url", "https://openrouter.ai/api/v1").resolve(catalog = CATALOG)
        r.cloud shouldBe true
        r.catalogProvider shouldBe "openrouter"
        opts("--provider", "openai-compat", "--model", "m", "--base-url", "https://api.openai.com/v1").resolve(catalog = CATALOG)
            .catalogProvider shouldBe "openai"
    }

    test("tailnet addresses and bare LAN names are local") {
        isLocalHost("100.101.2.3") shouldBe true
        isLocalHost("gpu-box") shouldBe true
        isLocalHost("host.docker.internal") shouldBe true
        isLocalHost("api.together.xyz") shouldBe false
        isLocalHost("8.8.8.8") shouldBe false
    }

    test("a model switch on a local openai-compat server never takes the catalog's window") {
        val r = opts("--provider", "openai-compat", "--model", "m", "--base-url", "http://192.168.1.5:8080/v1").resolve(catalog = CATALOG)
        modelSwitchWindow(r, CATALOG, "claude-x", current = 8_000) shouldBe ModelSwitch(window = 8_000)
        val cloud = opts("--provider", "claude", "--model", "claude-x").resolve(catalog = CATALOG)
        modelSwitchWindow(cloud, CATALOG, "claude-x", current = 8_000) shouldBe ModelSwitch(window = 123456)
        modelSwitchWindow(cloud, CATALOG, "unknown", current = 8_000) shouldBe ModelSwitch(window = 8_000)
        val local = r.copy(server = LocalServer(LocalServerKind.LLAMA_CPP, "http://127.0.0.1:1/v1", listOf("a")))
        modelSwitchWindow(local, CATALOG, "b", current = 8_000).error shouldBe "b isn't on the server at http://127.0.0.1:1/v1"
        modelSwitchWindow(local, CATALOG, "a", current = 8_000, detect = { _, _ -> 4_096 }) shouldBe ModelSwitch(window = 4_096)
    }
})
