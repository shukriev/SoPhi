package dev.sophi.extensions

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.core.agent.HookResult
import dev.sophi.core.agent.LoopHooks
import dev.sophi.core.tools.ConfirmationRequest
import java.util.ServiceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registry for [SophiPlugin] instances discovered via [ServiceLoader] or registered manually.
 * Pass it to AgentLoop as its [LoopHooks] and the loop fires the tool, request, confirmation and compaction hooks.
 *
 * **Thread-safety:** Not thread-safe. All [register] and [discover] calls must complete before
 * any [dispatch] or [hooksFor] calls begin. Concurrent mutation and dispatch is not supported.
 */
class PluginRegistry(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader
) : LoopHooks {
    private val _plugins: MutableList<SophiPlugin> = mutableListOf()

    fun register(plugin: SophiPlugin): PluginRegistry {
        _plugins.add(plugin)
        return this
    }

    fun discover(): PluginRegistry {
        ServiceLoader.load(SophiPlugin::class.java, classLoader).forEach { _plugins.add(it) }
        return this
    }

    fun plugins(): List<SophiPlugin> = _plugins.toList()

    fun hooksFor(point: HookPoint): List<AgentHook> =
        _plugins.flatMap { it.hooks() }.filter { it.point == point }

    suspend fun dispatch(point: HookPoint, context: HookContext) {
        hooksFor(point).forEach { it.decide(context) }
    }

    /** Rewrites chain in registration order; the first veto wins. */
    override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String): HookResult {
        var args = argumentsJson
        for (hook in hooksFor(HookPoint.BEFORE_TOOL)) {
            when (val r = hook.decide(HookContext(sessionId, toolName = name, argumentsJson = args))) {
                is HookResult.Veto -> return r
                is HookResult.Rewrite -> args = r.argumentsJson
                else -> Unit
            }
        }
        return if (args == argumentsJson) HookResult.Continue else HookResult.Rewrite(args)
    }

    override suspend fun afterTool(sessionId: String, name: String, argumentsJson: String, result: String, failed: Boolean, durationMillis: Long) =
        dispatch(HookPoint.AFTER_TOOL, HookContext(sessionId, toolName = name, argumentsJson = argumentsJson,
            toolResult = result, success = !failed, durationMillis = durationMillis))

    override suspend fun beforeRequest(sessionId: String, request: CompletionRequest): CompletionRequest =
        hooksFor(HookPoint.BEFORE_PROVIDER_REQUEST).fold(request) { req, hook ->
            (hook.decide(HookContext(sessionId, request = req)) as? HookResult.Request)?.request ?: req
        }

    override suspend fun compaction(sessionId: String, after: Boolean) =
        dispatch(if (after) HookPoint.AFTER_COMPACTION else HookPoint.BEFORE_COMPACTION, HookContext(sessionId))

    override suspend fun denies(sessionId: String, request: ConfirmationRequest): String? =
        hooksFor(HookPoint.ON_CONFIRMATION).firstNotNullOfOrNull { hook ->
            (hook.decide(HookContext(sessionId, toolName = request.toolName, argumentsJson = request.argumentsJson)) as? HookResult.Veto)?.reason
        }

    /**
     * Collects per-turn context from every plugin that implements [ContextContributor],
     * in registration order. Each contributor runs under its own timeout and try/catch:
     * a slow or failing contributor yields nothing and never breaks the turn.
     */
    suspend fun collectContext(
        sessionId: String,
        userInput: String,
        timeoutMillis: Long = 2_000
    ): List<String> = collectPlacedContext(sessionId, userInput, timeoutMillis).map { it.text }

    /** [collectContext] with each contribution's [Placement] kept, for callers that place them. */
    suspend fun collectPlacedContext(
        sessionId: String,
        userInput: String,
        timeoutMillis: Long = 2_000
    ): List<PlacedContext> = _plugins.filterIsInstance<ContextContributor>().mapNotNull { contributor ->
        try {
            withTimeoutOrNull(timeoutMillis) { contributor.contribute(sessionId, userInput) }
                ?.let { PlacedContext(contributor.placement, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

data class PlacedContext(val placement: Placement, val text: String)
