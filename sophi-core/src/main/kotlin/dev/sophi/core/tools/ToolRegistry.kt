package dev.sophi.core.tools

import dev.sophi.ai.api.ToolDefinition

class ToolRegistry {
    // Replaced whole on each write, never mutated: an MCP server's list change registers from another thread
    // while a turn reads, and each read sees one consistent, ordered snapshot.
    @Volatile private var tools: Map<String, Tool> = emptyMap()

    @Synchronized
    fun register(tool: Tool): ToolRegistry {
        tools = tools + (tool.name to tool)
        return this
    }

    @Synchronized
    fun unregister(name: String): ToolRegistry {
        tools = tools - name
        return this
    }

    fun get(name: String): Tool =
        tools[name] ?: throw NoSuchElementException("Tool not found: $name")

    fun getOrNull(name: String): Tool? = tools[name]

    /**
     * Every tool except deferred ones not in [activated]. Deferral only applies while
     * [TOOL_SEARCH_NAME] is registered; without it the model could never load a hidden tool.
     */
    fun definitions(activated: Set<String> = emptySet()): List<ToolDefinition> {
        val tools = tools
        val hideDeferred = TOOL_SEARCH_NAME in tools
        return tools.values
            .filter { !(hideDeferred && it.deferred && it.name !in activated) }
            .map { ToolDefinition(it.name, it.description, it.parametersJson) }
    }

    fun deferredNames(): List<String> = tools.values.filter { it.deferred }.map { it.name }.sorted()

    /** Registers [ToolSearchTool] once there is a deferred tool and none registered yet. */
    @Synchronized
    fun registerToolSearchIfDeferred(): ToolRegistry {
        if (TOOL_SEARCH_NAME !in tools && deferredNames().isNotEmpty()) register(ToolSearchTool(this))
        return this
    }

    fun names(): List<String> = tools.keys.sorted()

    /**
     * A kept [ToolSearchTool] is rebound to the subset, so it never offers tools the subset lacks. `result_read` is
     * always kept: a long result in the subset's loop names it.
     */
    fun subset(names: List<String>): ToolRegistry {
        val filtered = ToolRegistry()
        (names + RESULT_READ_NAME).forEach { name ->
            tools[name]?.let { filtered.register(if (it is ToolSearchTool) ToolSearchTool(filtered) else it) }
        }
        return filtered
    }

    /**
     * The subset of [expectedTools] safe to auto-grant (bypass confirmation for) without ever
     * seeing the real call arguments — e.g. a model's self-declared `expected_tools` before any
     * actual tool call exists. A name is kept only if this registry's tool for it reports
     * [RiskLevel.SAFE] when probed with empty arguments (`"{}"`, standing in for "no real
     * arguments available yet").
     *
     * This makes the empty-args probe a real safety boundary: every [Tool.riskLevel] override
     * that can return a non-SAFE tier MUST fail closed (non-SAFE) when its arguments don't parse
     * — a tool that defaults to SAFE on unparseable input defeats this check, since `grants`
     * membership is trusted for every subsequent real call to that tool name, not re-validated
     * per call (see AgentLoop's grants handling). ScheduleTaskTool.riskLevel is the reference
     * example: it fails closed to DESTRUCTIVE, matching this contract.
     */
    fun safeGrantsFrom(expectedTools: List<String>?): Set<String> =
        expectedTools.orEmpty().filter { getOrNull(it)?.riskLevel("{}") == RiskLevel.SAFE }.toSet()

    /**
     * The worst (most restrictive) risk tier among [expectedTools], probed the same way
     * [safeGrantsFrom] does — empty arguments ("{}"), since no real call exists yet. Shared by
     * SubagentTool/DecomposeGoalTool to report their own risk level from a caller's self-declared
     * expected tools.
     */
    /** Every kind the named tools may have, probed with "{}" like [worstRiskAmong]; none named or one unknown = all kinds. */
    fun effectsAmong(expectedTools: List<String>?): Set<EffectKind> {
        if (expectedTools.isNullOrEmpty()) return EffectKind.entries.toSet()
        return expectedTools.flatMap { getOrNull(it)?.callEffects("{}") ?: EffectKind.entries }.toSet()
    }

    fun worstRiskAmong(expectedTools: List<String>?): RiskLevel {
        val tiers = expectedTools.orEmpty().mapNotNull { getOrNull(it)?.riskLevel("{}") }
        return when {
            RiskLevel.DESTRUCTIVE in tiers -> RiskLevel.DESTRUCTIVE
            RiskLevel.CAUTION in tiers -> RiskLevel.CAUTION
            else -> RiskLevel.SAFE
        }
    }
}
