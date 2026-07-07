package dev.sophi.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the subprocess leak described in the Task 3 code review:
 * [StdioMcpConnector.connect] spawns a subprocess via [ProcessBuilder] before attempting the MCP
 * handshake; if the handshake throws (server crashes, hangs, or never speaks the protocol), the
 * already-started child must be force-killed rather than leaked.
 *
 * Exercising this through a *real* failed handshake against the actual MCP SDK turned out to be
 * unreliable: engineering a fast, deterministic handshake failure requires either the SDK's real
 * initialize timeout (60s, not overridable through [StdioMcpConnector]'s public surface) or
 * closing the child's stdout to force an EOF — which, empirically, raced with the SDK's own
 * transport-close/retry logic and sometimes hung for many minutes instead of failing fast (this
 * was diagnosed with a plain OS-level check, confirming the OS side sees EOF in ~20ms, so the
 * hang lived inside the SDK's own close/timeout handling, not in anything under test here).
 *
 * So this test exercises the extracted [StdioMcpConnector.connectOrDestroy] helper directly with
 * a synthetic failure instead of going through a real (flaky-timed) SDK handshake. That helper is
 * exactly the "attempt the handshake, force-destroy the already-started process on any failure"
 * logic from the review finding, and this test proves it deterministically and in milliseconds: a
 * real, long-lived OS process (`sleep 30`) is spawned, [connectOrDestroy] is made to fail
 * synthetically, and the test asserts the process is no longer alive afterward — i.e. it was
 * killed by the fix, not left to run out its 30-second sleep on its own.
 *
 * The full spawn-and-connect path (`StdioMcpConnector.connect`'s happy path) continues to be
 * covered by [StdioMcpConnectorTest], which talks to a real fixture MCP server over stdio.
 */
class StdioMcpConnectorLeakTest : FunSpec({

    test("connectOrDestroy force-destroys the process when the handshake block throws") {
        val process = ProcessBuilder("sleep", "30").start()
        val connector = StdioMcpConnector()

        process.isAlive shouldBe true

        val result = runCatching {
            runBlocking {
                connector.connectOrDestroy(process) {
                    throw IllegalStateException("simulated handshake failure")
                }
            }
        }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "simulated handshake failure"

        // destroyForcibly() sends the kill signal but doesn't block for the OS to reap the
        // process, so give it a brief, bounded window before asserting it's actually gone.
        process.waitFor(2, TimeUnit.SECONDS)
        process.isAlive shouldBe false
    }

    test("connectOrDestroy leaves the process running and returns normally when the handshake block succeeds") {
        val process = ProcessBuilder("sleep", "30").start()
        val connector = StdioMcpConnector()

        try {
            val client = runBlocking {
                connector.connectOrDestroy(process) { "not a real Client, just proving the success path" as Any }
            }

            client shouldBe "not a real Client, just proving the success path"
            process.isAlive shouldBe true
        } finally {
            process.destroyForcibly()
        }
    }
})
