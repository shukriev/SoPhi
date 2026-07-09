package dev.sophi.web

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.web.api.ChatRequest
import dev.sophi.web.api.ChatResponse
import dev.sophi.web.api.SessionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api")
class AgentController(
    private val sessionManager: SessionManager,
    private val agentLoop: AgentLoop,
    private val config: AgentConfig
) {
    // Concurrent turns on one session would each load-then-save, losing the
    // slower writer's entries; serialize load+turn+save per session id.
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = sessionLocks.computeIfAbsent(id) { Mutex() }

    @PostMapping("/sessions")
    fun createSession(@RequestParam(required = false) title: String?): SessionDto {
        val session = sessionManager.create(title)
        return SessionDto(session.id, session.entries.size, System.currentTimeMillis())
    }

    @GetMapping("/sessions")
    fun listSessions(): List<SessionDto> =
        sessionManager.list().map { SessionDto(it.id, it.entryCount, it.lastModifiedMillis) }

    @PostMapping("/sessions/{id}/turn")
    suspend fun turn(
        @PathVariable id: String,
        @RequestBody req: ChatRequest
    ): ResponseEntity<ChatResponse> = lockFor(id).withLock {
        val session = try {
            sessionManager.load(id)
        } catch (e: Exception) {
            return ResponseEntity.notFound().build()
        }
        val updated = agentLoop.turn(session, req.input, config)
        val reply = updated.branch().lastOrNull { it.role == EntryRole.ASSISTANT }?.content ?: ""
        ResponseEntity.ok(ChatResponse(updated.id, reply))
    }

    @GetMapping("/sessions/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamTurn(
        @PathVariable id: String,
        @RequestParam input: String
    ): SseEmitter {
        val emitter = SseEmitter(30_000L)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                lockFor(id).withLock {
                    val session = sessionManager.load(id)
                    agentLoop.streamTurn(session, input, config) { event ->
                        if (event is TurnEvent.Token) emitter.send(SseEmitter.event().data(event.text).build())
                    }
                }
                emitter.complete()
            } catch (e: Exception) {
                emitter.completeWithError(e)
            }
        }
        return emitter
    }
}
