package dev.sophi.hub

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class HubClientTest : FunSpec({
    test("connect() returns true and publish() reaches the server's events flow") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HubClient(port, sessionId = "s1")
                    val connected = client.connect(this)
                    connected shouldBe true

                    client.publish(HubEvent.SessionRegistered("s1", "t", 1L, "/repo"))
                    server.events.first() shouldBe HubEvent.SessionRegistered("s1", "t", 1L, "/repo")

                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    test("connect() returns false, does not throw, when nothing is listening on the port") {
        val port = freePort() // nothing bound here
        runBlocking {
            val client = HubClient(port, sessionId = "s1")
            client.connect(this) shouldBe false
            client.close()
        }
    }

    test("publish() before a successful connect() is a silent no-op") {
        val port = freePort()
        runBlocking {
            val client = HubClient(port, sessionId = "s1")
            client.connect(this) // fails, nothing listening
            client.publish(HubEvent.Token("s1", "hi")) // must not throw
            client.close()
        }
    }

    test("commands emitted by the server via sendCommand arrive on the client's commands flow") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HubClient(port, sessionId = "s1")
                    client.connect(this)
                    client.publish(HubEvent.SessionRegistered("s1", "t", 1L, "/repo"))
                    delay(200) // let the server record the registration

                    val received = async { client.commands.first() }
                    delay(100)
                    server.sendCommand(HubCommand.SendMessage("s1", "hi from companion"))

                    received.await() shouldBe HubCommand.SendMessage("s1", "hi from companion")
                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }
})
