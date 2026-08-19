package dev.sophi.web

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.learning.LearningPlugin
import dev.sophi.web.api.ChatRequest
import dev.sophi.web.api.ChatResponse
import dev.sophi.web.api.FeedbackRequest
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

internal fun sseEventFor(event: TurnEvent): SseEmitter.SseEventBuilder? = when (event) {
    is TurnEvent.Token -> SseEmitter.event().data(event.text)
    is TurnEvent.ReasoningToken -> SseEmitter.event().name("reasoning").data(event.text)
    else -> null
}

@RestController
@RequestMapping("/api")
class AgentController(
    private val sessionManager: SessionManager,
    private val agentLoop: AgentLoop,
    private val config: AgentConfig,
    private val pluginRegistry: PluginRegistry = PluginRegistry(),
    private val learningPlugin: LearningPlugin? = null
) {
    // Concurrent turns on one session would each load-then-save, losing the
    // slower writer's entries; serialize load+turn+save per session id.
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = sessionLocks.computeIfAbsent(id) { Mutex() }

    /**
     * Merges any plugin-contributed context (lessons, memory) into a per-turn AgentConfig, the
     * same collectContext path SophiRuntime.streamTurn already uses. Without this, sophi-web
     * never receives lesson-recall content: LearningPlugin.promptSections() is baked into [config]
     * once, statically, at bean-creation time, and no longer includes lessons — those are now
     * delivered per-turn via ContextContributor, which nothing here was calling.
     */
    private suspend fun configWithContext(sessionId: String, input: String): AgentConfig {
        val extra = pluginRegistry.collectContext(sessionId, input).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        return if (extra == null) config
        else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extra).joinToString("\n\n"))
    }

    @PostMapping("/sessions")
    fun createSession(@RequestParam(required = false) title: String?): SessionDto {
        val session = sessionManager.create(title)
        runCatching { sessionManager.saveConfigSnapshot(session.id, config.model, config.systemPrompt) }
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
        val updated = try {
            agentLoop.turn(session, req.input, configWithContext(id, req.input), pluginRegistry.turnEventBridge(id))
        } catch (e: Exception) {
            // Learning must never break responses: dispatch is best-effort, error still propagates.
            runCatching { pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(id, error = e)) }
            throw e
        }
        runCatching { pluginRegistry.dispatch(HookPoint.AFTER_TURN, HookContext(id)) }
        val reply = updated.branch().lastOrNull { it.role == EntryRole.ASSISTANT }?.content ?: ""
        ResponseEntity.ok(ChatResponse(updated.id, reply))
    }

    @PostMapping("/sessions/{id}/feedback")
    fun feedback(@PathVariable id: String, @RequestBody req: FeedbackRequest): ResponseEntity<Map<String, String>> {
        if (req.polarity !in setOf("positive", "negative"))
            return ResponseEntity.badRequest().body(mapOf("error" to "polarity must be positive|negative"))
        val plugin = learningPlugin
            ?: return ResponseEntity.status(503).body(mapOf("error" to "learning disabled"))
        val session = try {
            sessionManager.load(id)
        } catch (e: Exception) {
            return ResponseEntity.notFound().build()
        }
        val target = req.entryIndex ?: session.entries.indexOfLast {
            it.role == EntryRole.ASSISTANT && it.metadata["replay"] != "false"
        }
        if (target !in session.entries.indices)
            return ResponseEntity.badRequest().body(mapOf("error" to "no entry to rate"))
        plugin.recordExplicitFeedback(id, target, req.polarity, req.reason)
        return ResponseEntity.ok(mapOf("status" to "ok"))
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
                    val bridge = pluginRegistry.turnEventBridge(id)
                    agentLoop.streamTurn(session, input, configWithContext(id, input)) { event ->
                        bridge(event)
                        sseEventFor(event)?.let { emitter.send(it.build()) }
                    }
                }
                runCatching { pluginRegistry.dispatch(HookPoint.AFTER_TURN, HookContext(id)) }
                emitter.complete()
            } catch (e: Exception) {
                runCatching { pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(id, error = e)) }
                emitter.completeWithError(e)
            }
        }
        return emitter
    }
}
