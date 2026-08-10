package dev.sophi.hub

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.ServerSocket

private val json = Json { ignoreUnknownKeys = true }

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class HubServerTest : FunSpec({
    test("a HubEvent published by a connected CLI client shows up on events") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HttpClient(CIO) { install(WebSockets) }
                    val session = client.webSocketSession(host = "127.0.0.1", port = port, path = "/hub/cli")
                    val collected = async { server.events.first() }
                    delay(100) // let the collector above actually subscribe before we emit
                    session.send(Frame.Text(json.encodeToString(HubEvent.serializer(), HubEvent.SessionRegistered("s1", "t", 1L, "/repo"))))
                    collected.await() shouldBe HubEvent.SessionRegistered("s1", "t", 1L, "/repo")
                    session.close()
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    test("sendCommand routes to the CLI client registered for that sessionId") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HttpClient(CIO) { install(WebSockets) }
                    val session = client.webSocketSession(host = "127.0.0.1", port = port, path = "/hub/cli")
                    session.send(Frame.Text(json.encodeToString(HubEvent.serializer(), HubEvent.SessionRegistered("s1", "t", 1L, "/repo"))))
                    delay(200) // let the server-side route record the registration

                    server.sendCommand(HubCommand.SendMessage("s1", "hello"))

                    val frame = withTimeout(3000) { session.incoming.receive() } as Frame.Text
                    json.decodeFromString(HubCommand.serializer(), frame.readText()) shouldBe
                        HubCommand.SendMessage("s1", "hello")
                    session.close()
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    test("sendCommand for an unregistered sessionId does not throw") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking { server.sendCommand(HubCommand.SendMessage("no-such-session", "hi")) }
        } finally {
            server.stop()
        }
    }
})
