package dev.sophi.core.session

interface SessionManager {
    fun create(title: String? = null, parentSessionId: String? = null): AgentSession
    fun save(session: AgentSession)
    fun load(sessionId: String): AgentSession
    fun list(): List<SessionMeta>
    fun saveConfigSnapshot(sessionId: String, model: String, systemPrompt: String?) {}
    fun rename(sessionId: String, title: String) {}
    fun delete(sessionId: String) {}
}
