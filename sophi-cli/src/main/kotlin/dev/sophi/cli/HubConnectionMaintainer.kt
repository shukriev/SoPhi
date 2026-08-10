package dev.sophi.cli

import dev.sophi.hub.HubClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Keeps [hubClient] connected for as long as this coroutine runs: retries [HubClient.connect]
 * every [retryDelayMs] while disconnected (covers both "the companion wasn't running yet at CLI
 * startup" and "the companion restarted mid-session"), invoking [onConnected] once per
 * successful (re)connection. A no-op tick while already connected costs one [HubClient.isConnected]
 * check, not a fresh connection attempt.
 *
 * Callers launch this in a background coroutine; it runs until that coroutine is cancelled.
 */
internal suspend fun maintainHubConnection(
    hubClient: HubClient,
    scope: CoroutineScope,
    retryDelayMs: Long = 5000,
    onConnected: suspend () -> Unit
) {
    while (currentCoroutineContext().isActive) {
        if (!hubClient.isConnected && hubClient.connect(scope)) {
            onConnected()
        }
        delay(retryDelayMs)
    }
}
