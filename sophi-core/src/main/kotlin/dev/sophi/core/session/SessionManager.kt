package dev.sophi.core.session

interface SessionManager {
    fun create(title: String? = null): AgentSession
    fun save(session: AgentSession)
    fun load(sessionId: String): AgentSession
    fun list(): List<SessionMeta>
}
