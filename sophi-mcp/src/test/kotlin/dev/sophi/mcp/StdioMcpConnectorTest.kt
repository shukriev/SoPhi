package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempFile
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.file.attribute.PosixFilePermission
import kotlin.time.measureTime

class StdioMcpConnectorTest : FunSpec({

    test("connect spawns the server, discovers its tools, and calls ping") {
        val classpath = System.getProperty("java.class.path")
        val config = McpServerConfig(
            name = "test",
            transport = McpTransport.STDIO,
            command = listOf(
                "java",
                // kotlin-logging prints a one-line "active logger factory" diagnostic via println()
                // (bypassing slf4j/logback entirely) unless this is set. Since stdout is the MCP
                // stdio transport's JSON-RPC channel for this subprocess, that stray line would
                // otherwise corrupt the framing the SDK's ReadBuffer expects on the client side.
                "-Dkotlin-logging.logStartupMessage=false",
                "-cp",
                classpath,
                "dev.sophi.mcp.TestMcpServerMainKt"
            )
        )
        val connector = StdioMcpConnector()

        val (toolNames, reply) = runBlocking {
            val session = connector.connect(config)
            val tools = session.listTools()
            val reply = session.callTool("ping", "{}")
            session.close()
            tools.map { it.name } to reply
        }

        toolNames shouldBe listOf("ping", "path")
        reply shouldBe "pong"
    }

    test("resolveLoginShellPath resolves a real login shell's PATH") {
        val shell = System.getenv("SHELL") ?: "/bin/zsh"

        val path = resolveLoginShellPath(shell)

        path.shouldNotBeNull()
        path shouldContain "bin"
    }

    test("resolveLoginShellPath returns null instead of throwing for a nonexistent shell") {
        resolveLoginShellPath("/definitely/does/not/exist/shell").shouldBeNull()
    }

    test("resolveLoginShellPath returns null within its timeout when the shell never exits") {
        val hangingShell = createTempFile(suffix = ".sh").apply {
            writeText("#!/bin/sh\nsleep 10\n")
            setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        }

        val elapsed = measureTime {
            resolveLoginShellPath(hangingShell.toString(), timeoutSeconds = 1).shouldBeNull()
        }

        (elapsed.inWholeSeconds < 5) shouldBe true
    }

    test("connect injects the resolved login-shell PATH into the spawned process's environment") {
        val classpath = System.getProperty("java.class.path")
        val shell = System.getenv("SHELL") ?: "/bin/zsh"
        val connector = StdioMcpConnector(loginShell = shell)
        val config = McpServerConfig(
            name = "test", transport = McpTransport.STDIO,
            command = listOf(
                "java", "-Dkotlin-logging.logStartupMessage=false", "-cp", classpath, "dev.sophi.mcp.TestMcpServerMainKt"
            )
        )

        val reply = runBlocking {
            val session = connector.connect(config)
            val reply = session.callTool("path", "{}")
            session.close()
            reply
        }

        reply shouldBe resolveLoginShellPath(shell)
    }

    test("connect lets config.env override the resolved login-shell PATH") {
        val classpath = System.getProperty("java.class.path")
        val connector = StdioMcpConnector()
        val config = McpServerConfig(
            name = "test", transport = McpTransport.STDIO,
            command = listOf(
                "java", "-Dkotlin-logging.logStartupMessage=false", "-cp", classpath, "dev.sophi.mcp.TestMcpServerMainKt"
            ),
            env = mapOf("PATH" to "/custom/override")
        )

        val reply = runBlocking {
            val session = connector.connect(config)
            val reply = session.callTool("path", "{}")
            session.close()
            reply
        }

        reply shouldBe "/custom/override"
    }
})
