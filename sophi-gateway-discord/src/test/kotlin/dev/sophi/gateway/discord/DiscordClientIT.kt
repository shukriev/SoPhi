package dev.sophi.gateway.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Against real Discord, only when DISCORD_TEST_TOKEN holds a test bot's token (never in CI). */
class DiscordClientIT : FunSpec({
    val token = System.getenv("DISCORD_TEST_TOKEN")
    test("connects and says who it is").config(enabled = !token.isNullOrBlank()) {
        val client = DiscordClient({ token }, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.start()
        try {
            withTimeout(30_000) { client.status.first { it is DiscordStatus.Connected } }.shouldBeInstanceOf<DiscordStatus.Connected>()
        } finally { client.stop() }
    }
})
