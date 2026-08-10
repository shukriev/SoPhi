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

class HubEndToEndTest : FunSpec({
    test("a HubEvent and a HubCommand both round-trip between one HubClient and HubServer") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val client = HubClient(port, sessionId = "s1")
                    client.connect(this) shouldBe true
                    client.publish(HubEvent.SessionRegistered("s1", "smoke test", 1L, "/repo"))
                    delay(200)

                    // event: CLI -> hub -> "companion" (server.events, read in-process)
                    val eventReceived = async { server.events.first { it is HubEvent.Token } }
                    delay(50)
                    client.publish(HubEvent.Token("s1", "hi"))
                    eventReceived.await() shouldBe HubEvent.Token("s1", "hi")

                    // command: "companion" (server.sendCommand) -> hub -> CLI
                    val commandReceived = async { client.commands.first() }
                    delay(50)
                    server.sendCommand(HubCommand.SendMessage("s1", "hello"))
                    commandReceived.await() shouldBe HubCommand.SendMessage("s1", "hello")

                    client.close()
                }
            }
        } finally {
            server.stop()
        }
    }
})
