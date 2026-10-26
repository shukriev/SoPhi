package dev.sophi.core.agent

/**
 * Messages the user typed while a turn runs. A steer is read by the loop between rounds; a
 * follow-up waits for the turn to end and starts the next one. The UI thread and the turn
 * coroutine both touch it, so every access takes the one lock.
 */
class TurnInbox {
    data class Queued(val text: String, val followUp: Boolean)

    private val lock = Any()
    private val steers = mutableListOf<String>()
    private val followUps = mutableListOf<String>()

    fun steer(text: String) { if (text.isNotBlank()) synchronized(lock) { steers.add(text) } }

    fun followUp(text: String) { if (text.isNotBlank()) synchronized(lock) { followUps.add(text) } }

    fun drainSteers(): List<String> = synchronized(lock) { steers.toList().also { steers.clear() } }

    fun takeFollowUp(): String? = synchronized(lock) { followUps.removeFirstOrNull() }

    /** Steers then follow-ups, emptying both. */
    fun drainAll(): List<String> = synchronized(lock) { (steers + followUps).also { steers.clear(); followUps.clear() } }

    fun snapshot(): List<Queued> = synchronized(lock) {
        steers.map { Queued(it, false) } + followUps.map { Queued(it, true) }
    }
}
