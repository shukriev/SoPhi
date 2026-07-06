package dev.sophi.core.session

import java.util.UUID

class AgentSession(
    val id: String,
    val title: String? = null,
    val parentSessionId: String? = null,
    initialEntries: List<SessionEntry> = emptyList(),
    initialTipId: String? = null
) {
    private val _entries: MutableList<SessionEntry> = initialEntries.toMutableList()
    private var _tipId: String? = initialTipId ?: initialEntries.lastOrNull()?.id

    val tip: SessionEntry? get() = _entries.find { it.id == _tipId }
    val entries: List<SessionEntry> get() = _entries.toList()

    fun append(
        role: EntryRole,
        content: String,
        metadata: Map<String, String> = emptyMap()
    ): SessionEntry {
        val entry = SessionEntry(
            id = UUID.randomUUID().toString(),
            parentId = _tipId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            metadata = metadata
        )
        _entries.add(entry)
        _tipId = entry.id
        return entry
    }

    fun branch(): List<SessionEntry> {
        val byId = _entries.associateBy { it.id }
        val result = ArrayDeque<SessionEntry>()
        var current: SessionEntry? = tip
        while (current != null) {
            result.addFirst(current)
            current = current.parentId?.let { byId[it] }
        }
        return result.toList()
    }

    fun checkout(entryId: String) {
        require(_entries.any { it.id == entryId }) { "Entry not found: $entryId" }
        _tipId = entryId
    }
}
