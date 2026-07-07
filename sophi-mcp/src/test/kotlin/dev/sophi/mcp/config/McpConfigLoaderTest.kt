package dev.sophi.mcp.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class McpConfigLoaderTest : FunSpec({
    val loader = McpConfigLoader()

    test("load returns an empty config when the file does not exist") {
        val dir = createTempDirectory("sophi-mcp-test")
        loader.load(dir.resolve("mcp.json")) shouldBe McpConfig()
    }

    test("load parses a valid config file with stdio and http servers") {
        val dir = createTempDirectory("sophi-mcp-test")
        val file = dir.resolve("mcp.json")
        file.writeText(
            """
            {
              "servers": [
                {
                  "name": "filesystem",
                  "transport": "stdio",
                  "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
                  "safeTools": ["list_directory"]
                },
                {
                  "name": "docs",
                  "transport": "http",
                  "url": "https://example.com/mcp"
                }
              ]
            }
            """.trimIndent()
        )

        val config = loader.load(file)

        config.servers.size shouldBe 2
        config.servers[0] shouldBe McpServerConfig(
            name = "filesystem",
            transport = McpTransport.STDIO,
            command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
            safeTools = listOf("list_directory")
        )
        config.servers[1] shouldBe McpServerConfig(
            name = "docs",
            transport = McpTransport.HTTP,
            url = "https://example.com/mcp"
        )
    }

    test("load throws on malformed JSON") {
        val dir = createTempDirectory("sophi-mcp-test")
        val file = dir.resolve("mcp.json")
        file.writeText("{ not valid json")

        shouldThrow<SerializationException> { loader.load(file) }
    }
})
