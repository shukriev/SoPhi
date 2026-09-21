package dev.sophi.sdk

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.SophiPlugin
import dev.sophi.skills.SkillRegistry

/**
 * Recalls the site skill a turn is about, the way `MemoryPlugin` recalls memories — by code, on
 * the turn path — instead of a Markdown protocol asking the model to remember to look. Every other
 * durable-knowledge type in this system is already recalled this way; site skills were the
 * exception.
 *
 * [registrySupplier] is called per turn rather than captured once: [RuntimeBuilder.skillTools]
 * snapshots its registry at build time, and inheriting that would make a skill Sophi wrote minutes
 * ago invisible until restart.
 *
 * ponytail: re-reading the skills directory each turn, not caching with invalidation. It is a
 * handful of small Markdown files well inside collectContext's 2s budget; add a cache when a
 * directory large enough to matter actually exists.
 */
class SiteSkillRecallPlugin(
    private val registrySupplier: () -> SkillRegistry
) : SophiPlugin, ContextContributor {

    override val name = "site-skill-recall"

    override fun hooks(): List<AgentHook> = emptyList()

    override suspend fun contribute(sessionId: String, userInput: String): String? = runCatching {
        val registry = registrySupplier()
        val ids = registry.all().map { it.first }
        val matched = matchSiteSkill(userInput, ids) ?: return null
        val body = registry.get(matched)?.body ?: return null
        renderSitePointer(matched, body)
    }.getOrNull()
}
