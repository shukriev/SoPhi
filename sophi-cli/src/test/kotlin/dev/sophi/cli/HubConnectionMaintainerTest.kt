package dev.sophi.cli

import dev.sophi.hub.HubClient
import dev.sophi.hub.HubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class HubConnectionMaintainerTest : FunSpec({
    // Reproduces the reported bug: a sophi-cli session started before sophi-companion never
    // showed up in the companion's Sessions tab, because HubClient.connect() was only ever
    // attempted once, at CLI startup — a companion starting afterward had no way to be noticed.
    test("connects once a hub that wasn't reachable at startup becomes reachable later") {
        val port = freePort() // nothing listening yet — simulates the CLI starting first
        runBlocking {
            withTimeout(5000) {
                val client = HubClient(port, sessionId = "s1")
                var connectedCallbacks = 0
                val job = launch { maintainHubConnection(client, this, retryDelayMs = 100) { connectedCallbacks++ } }

                delay(250)
                client.isConnected shouldBe false
                connectedCallbacks shouldBe 0

                // The companion starts now, after the CLI session.
                val server = HubServer(port)
                server.start()
                try {
                    var attempts = 0
                    while (!client.isConnected && attempts < 50) {
                        delay(50)
                        attempts++
                    }
                    client.isConnected shouldBe true
                    connectedCallbacks shouldBe 1
                } finally {
                    server.stop()
                    job.cancel()
                    client.close()
                }
            }
        }
    }

    test("does not re-invoke the callback on every tick once already connected") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HubClient(port, sessionId = "s1")
                    var connectedCallbacks = 0
                    val job = launch { maintainHubConnection(client, this, retryDelayMs = 50) { connectedCallbacks++ } }

                    var attempts = 0
                    while (!client.isConnected && attempts < 50) {
                        delay(20)
                        attempts++
                    }
                    delay(300) // several more retry ticks while already connected

                    connectedCallbacks shouldBe 1
                    job.cancel()
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    test("reconnects and re-invokes the callback after the hub drops and comes back") {
        val port = freePort()
        var server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(8000) {
                    val client = HubClient(port, sessionId = "s1")
                    var connectedCallbacks = 0
                    val job = launch { maintainHubConnection(client, this, retryDelayMs = 100) { connectedCallbacks++ } }

                    var attempts = 0
                    while (!client.isConnected && attempts < 50) {
                        delay(50)
                        attempts++
                    }
                    connectedCallbacks shouldBe 1

                    server.stop()
                    attempts = 0
                    while (client.isConnected && attempts < 50) {
                        delay(50)
                        attempts++
                    }
                    client.isConnected shouldBe false

                    server = HubServer(port)
                    server.start()
                    attempts = 0
                    while (!client.isConnected && attempts < 50) {
                        delay(50)
                        attempts++
                    }
                    client.isConnected shouldBe true
                    connectedCallbacks shouldBe 2

                    job.cancel()
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }
})
