package dev.sophi.cli

import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import dev.sophi.hub.HubClient
import dev.sophi.hub.HubCommand
import dev.sophi.hub.HubEvent
import dev.sophi.hub.HubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private val bashRequest = ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE)

// A terminal stand-in whose confirm() only returns once release() is called — lets tests force
// which side of the race wins deterministically instead of relying on timing.
private class ControllableTerminalPolicy : ConfirmationPolicy {
    private val gate = CompletableDeferred<Map<String, Boolean>>()
    fun release(result: Map<String, Boolean>) = gate.complete(result)
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> = gate.await()
}

class RemoteAwareConfirmationPolicyTest : FunSpec({
    test("falls through to the terminal policy when hubClient is null") {
        val terminal = ControllableTerminalPolicy()
        val policy = RemoteAwareConfirmationPolicy(terminal, hubClient = { null }, sessionId = { "s1" })
        runBlocking {
            val result = async { policy.confirm(listOf(bashRequest)) }
            delay(50)
            terminal.release(mapOf("c1" to true))
            result.await() shouldBe mapOf("c1" to true)
        }
    }

    test("terminal answer wins when it resolves first") {
        val terminal = ControllableTerminalPolicy()
        runBlocking {
            withTimeout(5000) {
                val policy = RemoteAwareConfirmationPolicy(terminal, hubClient = { null }, sessionId = { "s1" })
                val result = async { policy.confirm(listOf(bashRequest)) }
                delay(50)
                terminal.release(mapOf("c1" to true))
                result.await() shouldBe mapOf("c1" to true)
            }
        }
    }

    test("remote answer wins when the hub responds before the terminal") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        try {
            runBlocking {
                withTimeout(5000) {
                    val hubClient = HubClient(port, sessionId = "s1")
                    hubClient.connect(this)
                    hubClient.publish(HubEvent.SessionRegistered("s1", null, 1L, "/repo"))
                    delay(200) // let the server record the registration

                    val terminal = ControllableTerminalPolicy() // never released in this test
                    val policy = RemoteAwareConfirmationPolicy(terminal, { hubClient }, sessionId = { "s1" })
                    val result = async { policy.confirm(listOf(bashRequest)) }
                    delay(200) // let confirm() publish ConfirmationRequested and subscribe

                    server.sendCommand(HubCommand.ConfirmationResponse("s1", "c1", approved = false))

                    result.await() shouldBe mapOf("c1" to false)
                    hubClient.close()
                }
            }
        } finally {
            server.stop()
        }
    }
})
