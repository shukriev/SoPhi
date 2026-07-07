package dev.sophi.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for exception-safety of [SdkMcpSession.close].
 *
 * [close] wraps [client.close] in try/finally to ensure [process?.destroyForcibly] always runs,
 * even if the client throws (e.g., server crashed, already-broken pipe). Without this protection,
 * a misbehaving server could reintroduce a narrower process-leak bug.
 */
class SdkMcpSessionTest : FunSpec({

    test("close destroys the process even if client.close() throws") {
        val process = ProcessBuilder("sleep", "30").start()
        val mockClient = mockk<io.modelcontextprotocol.kotlin.sdk.client.Client>()
        coEvery { mockClient.close() } throws IllegalStateException("simulated client close failure")

        process.isAlive shouldBe true

        val session = SdkMcpSession(mockClient, process)
        val result = runCatching {
            runBlocking {
                session.close()
            }
        }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "simulated client close failure"

        // destroyForcibly() sends the kill signal but doesn't block for the OS to reap the
        // process, so give it a brief, bounded window before asserting it's actually gone.
        process.waitFor(2, TimeUnit.SECONDS)
        process.isAlive shouldBe false
    }

    test("close normally closes the client and destroys the process on success") {
        val process = ProcessBuilder("sleep", "30").start()
        val mockClient = mockk<io.modelcontextprotocol.kotlin.sdk.client.Client>()
        coEvery { mockClient.close() } returns Unit

        try {
            process.isAlive shouldBe true

            val session = SdkMcpSession(mockClient, process)
            runBlocking {
                session.close()
            }

            // Process should be destroyed after close()
            process.waitFor(2, TimeUnit.SECONDS)
            process.isAlive shouldBe false
        } finally {
            // Safety fallback to ensure cleanup
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    test("close works correctly when there is no process") {
        val mockClient = mockk<io.modelcontextprotocol.kotlin.sdk.client.Client>()
        coEvery { mockClient.close() } returns Unit

        val session = SdkMcpSession(mockClient, null)
        // Should not throw
        runBlocking {
            session.close()
        }
    }

    // The real SDK's StreamableHttpClientTransport.closeResources() only cancels its own SSE
    // job/scope and never calls the underlying HttpClient's close() (verified against the
    // 0.14.0 SDK sources) — so SdkMcpSession must close an externally-provided HttpClient itself,
    // or every HTTP session close leaks the CIO engine's connection pool and worker threads.
    test("close closes the http client when one was supplied") {
        val mockClient = mockk<io.modelcontextprotocol.kotlin.sdk.client.Client>()
        coEvery { mockClient.close() } returns Unit
        val mockHttpClient = mockk<HttpClient>()
        every { mockHttpClient.close() } returns Unit

        val session = SdkMcpSession(mockClient, process = null, httpClient = mockHttpClient)
        runBlocking {
            session.close()
        }

        verify { mockHttpClient.close() }
    }

    test("close closes the http client even if client.close() throws") {
        val mockClient = mockk<io.modelcontextprotocol.kotlin.sdk.client.Client>()
        coEvery { mockClient.close() } throws IllegalStateException("simulated client close failure")
        val mockHttpClient = mockk<HttpClient>()
        every { mockHttpClient.close() } returns Unit

        val session = SdkMcpSession(mockClient, process = null, httpClient = mockHttpClient)
        val result = runCatching {
            runBlocking {
                session.close()
            }
        }

        result.isFailure shouldBe true
        verify { mockHttpClient.close() }
    }
})
