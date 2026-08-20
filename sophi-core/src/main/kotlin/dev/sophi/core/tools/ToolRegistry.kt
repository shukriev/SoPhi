package dev.sophi.core.tools

import dev.sophi.ai.api.ToolDefinition

class ToolRegistry {
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    fun register(tool: Tool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun unregister(name: String): ToolRegistry {
        tools.remove(name)
        return this
    }

    fun get(name: String): Tool =
        tools[name] ?: throw NoSuchElementException("Tool not found: $name")

    fun getOrNull(name: String): Tool? = tools[name]

    fun definitions(): List<ToolDefinition> =
        tools.values.map { ToolDefinition(it.name, it.description, it.parametersJson) }

    fun names(): List<String> = tools.keys.sorted()

    fun subset(names: List<String>): ToolRegistry {
        val filtered = ToolRegistry()
        names.forEach { name -> tools[name]?.let { filtered.register(it) } }
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
    fun worstRiskAmong(expectedTools: List<String>?): RiskLevel {
        val tiers = expectedTools.orEmpty().mapNotNull { getOrNull(it)?.riskLevel("{}") }
        return when {
            RiskLevel.DESTRUCTIVE in tiers -> RiskLevel.DESTRUCTIVE
            RiskLevel.CAUTION in tiers -> RiskLevel.CAUTION
            else -> RiskLevel.SAFE
        }
    }
}
