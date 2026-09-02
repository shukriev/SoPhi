package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

    test("resolveLoginShellPath invokes the shell interactively, not just as a login shell") {
        // zsh only sources ~/.zshrc — where most people's Homebrew/node PATH exports actually
        // live — for an interactive shell. A login-only invocation silently resolves the wrong
        // PATH instead of failing, so this must be caught as a regression, not just "does it
        // return something non-null".
        val fakeShell = createTempFile(suffix = ".sh").apply {
            writeText(
                """
                #!/bin/sh
                for arg in "$@"; do
                  if [ "${'$'}arg" = "-i" ]; then printf '%s' "interactive"; exit 0; fi
                done
                printf '%s' "not-interactive"
                """.trimIndent()
            )
            setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        }

        resolveLoginShellPath(fakeShell.toString()) shouldBe "interactive"
    }

    test("resolveLoginShellPath returns only the final output line, ignoring a startup banner") {
        val fakeShell = createTempFile(suffix = ".sh").apply {
            writeText(
                """
                #!/bin/sh
                echo "Welcome banner noise from oh-my-zsh or similar"
                printf '%s' "/real/path/bin:/usr/bin"
                """.trimIndent()
            )
            setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        }

        resolveLoginShellPath(fakeShell.toString()) shouldBe "/real/path/bin:/usr/bin"
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

    test("resolveExecutable leaves an already-qualified command untouched") {
        resolveExecutable(listOf("/usr/bin/env", "node"), path = "/nonexistent") shouldBe listOf("/usr/bin/env", "node")
    }

    test("resolveExecutable rewrites a bare command name to the absolute path found on the given PATH") {
        val binDir = createTempFile(suffix = "").parent!!
        val exe = binDir.resolve("fake-npx")
        exe.apply {
            writeText("#!/bin/sh\necho hi\n")
            setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        }

        resolveExecutable(listOf("fake-npx", "-y", "@playwright/mcp"), path = binDir.toString()) shouldBe
            listOf(exe.toString(), "-y", "@playwright/mcp")
    }

    test("resolveExecutable falls back to the original command when the name isn't found anywhere on PATH") {
        resolveExecutable(listOf("definitely-not-a-real-command"), path = "/nonexistent") shouldBe
            listOf("definitely-not-a-real-command")
    }

    test("connect times out and kills the process when the server never completes the handshake") {
        val config = McpServerConfig(
            name = "hanging", transport = McpTransport.STDIO,
            // A real process that starts but never speaks the MCP protocol on stdout — the
            // handshake never completes, so without a timeout this would hang forever.
            command = listOf("sleep", "30")
        )
        val connector = StdioMcpConnector(connectTimeoutSeconds = 1)

        val elapsed = measureTime {
            val error = runCatching { runBlocking { connector.connect(config) } }.exceptionOrNull()
            error.shouldNotBeNull()
            error.shouldBeInstanceOf<McpConnectTimeoutException>()
            error.message shouldContain "hanging"
        }

        (elapsed.inWholeSeconds < 5) shouldBe true
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
