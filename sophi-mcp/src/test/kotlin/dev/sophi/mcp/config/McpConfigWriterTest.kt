package dev.sophi.mcp.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class McpConfigWriterTest : FunSpec({
    val writer = McpConfigWriter()
    val loader = McpConfigLoader()

    test("write then load round-trips a config with multiple servers") {
        val dir = createTempDirectory("sophi-mcp-writer-test")
        val path = dir.resolve("mcp.json")
        val config = McpConfig(
            servers = listOf(
                McpServerConfig(name = "filesystem", transport = McpTransport.STDIO, command = listOf("npx", "fs-server")),
                McpServerConfig(name = "docs", transport = McpTransport.HTTP, url = "https://example.com/mcp", safeTools = listOf("search"), enabled = false)
            )
        )

        writer.write(path, config)
        val loaded = loader.load(path)

        loaded shouldBe config
    }

    test("write creates parent directories if they don't exist") {
        val dir = createTempDirectory("sophi-mcp-writer-test")
        val path = dir.resolve("nested/dir/mcp.json")

        writer.write(path, McpConfig(servers = emptyList()))

        loader.load(path) shouldBe McpConfig(servers = emptyList())
    }

    test("write overwrites an existing file") {
        val dir = createTempDirectory("sophi-mcp-writer-test")
        val path = dir.resolve("mcp.json")
        writer.write(path, McpConfig(servers = listOf(McpServerConfig(name = "a", transport = McpTransport.STDIO))))

        writer.write(path, McpConfig(servers = listOf(McpServerConfig(name = "b", transport = McpTransport.STDIO))))

        loader.load(path).servers.map { it.name } shouldBe listOf("b")
    }
})
