package dev.sophi.extensions

import java.util.ServiceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registry for [SophiPlugin] instances discovered via [ServiceLoader] or registered manually.
 *
 * **Thread-safety:** Not thread-safe. All [register] and [discover] calls must complete before
 * any [dispatch] or [hooksFor] calls begin. Concurrent mutation and dispatch is not supported.
 */
class PluginRegistry(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader
) {
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
        hooksFor(point).forEach { it.invoke(context) }
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
    ): List<String> = _plugins.filterIsInstance<ContextContributor>().mapNotNull { contributor ->
        try {
            withTimeoutOrNull(timeoutMillis) { contributor.contribute(sessionId, userInput) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
