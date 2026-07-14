package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LLM-free room routing (spec §6): each room has a short descriptor, embedded once and
 * cached; cosine(query, descriptor) picks the active rooms. Room descriptors deliberately
 * echo the encoder's room definitions so routing and encoding agree.
 */
class RoomRouter(private val embeddings: EmbeddingProvider) {
    private val descriptors = linkedMapOf(
        Room.ENTITIES to "people organizations pets places names family friends colleagues doctors recurring services in the user's life",
        Room.TASKS to "tasks errands appointments reminders deadlines schedules goals routines todo",
        Room.EPISODES to "events conversations decisions things that happened stories reported life events",
        Room.KNOWLEDGE to "durable facts job domain hobbies home health context how the user's world works",
        Room.NARRATIVE to "cause and effect story threads life changes journeys because therefore led to"
    )
    private var vectors: Map<Room, FloatArray>? = null
    private val mutex = Mutex()

    suspend fun route(queryVector: FloatArray, topK: Int): List<Room> {
        val vecs = mutex.withLock {
            vectors ?: embeddings.embed(descriptors.values.toList())
                .let { embedded -> descriptors.keys.zip(embedded).toMap() }
                .also { vectors = it }
        }
        return vecs.entries.sortedByDescending { cosine(queryVector, it.value) }
            .take(topK).map { it.key }
    }
}
