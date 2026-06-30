package dev.sophi.extensions

import java.util.ServiceLoader

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
}
