package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.learning.LearningPlugin

class SlashHandler(
    private val sessionManager: SessionManager,
    private val compactor: ContextCompactor?,
    private val config: AgentConfig,
    private val learning: LearningPlugin? = null,
    private val output: (String) -> Unit
) {
    suspend fun handle(line: String, session: AgentSession): AgentSession {
        val parts = line.trimStart('/').split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim()
        return when (cmd) {
            "list" -> {
                val sessions = sessionManager.list()
                if (sessions.isEmpty()) output("No saved sessions.")
                else sessions.forEach { output("${it.id}  ${it.entryCount} entries") }
                session
            }
            "branch" -> {
                val entries = session.branch()
                if (entries.isEmpty()) output("(empty)")
                else entries.forEachIndexed { i, e ->
                    output("${i + 1}. [${e.role}] ${e.id}  ${e.content.take(60)}")
                }
                session
            }
            "checkout" -> {
                if (arg.isNullOrEmpty()) {
                    output("Usage: /checkout <entry-id>")
                } else {
                    try {
                        session.checkout(arg)
                        output("Checked out entry $arg")
                    } catch (e: Exception) {
                        output("Error: ${e.message}")
                    }
                }
                session
            }
            "compact" -> {
                if (compactor == null) {
                    output("No compactor configured.")
                    session
                } else {
                    val compacted = compactor.compact(session, config)
                    sessionManager.save(compacted)
                    output("Compacted to ${compacted.branch().size} entries.")
                    compacted
                }
            }
            "good", "bad" -> {
                if (learning == null) { output("Learning is not enabled."); session }
                else {
                    val target = session.entries.indexOfLast {
                        it.role == EntryRole.ASSISTANT && it.metadata["replay"] != "false"
                    }
                    if (target < 0) output("Nothing to rate yet.")
                    else {
                        learning.recordExplicitFeedback(
                            session.id, target,
                            if (cmd == "good") "positive" else "negative",
                            arg?.takeIf { it.isNotBlank() })
                        output("Noted.")
                    }
                    session
                }
            }
            else -> {
                output("Unknown command: /$cmd  Available: /list /branch /checkout /compact /good /bad")
                session
            }
        }
    }
}
