package dev.sophi.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationCenter(private val store: NotificationStore) {
    private val _records = MutableStateFlow(store.list().reversed())
    val records: StateFlow<List<NotificationRecord>> = _records.asStateFlow()

    fun add(kind: NotificationKind, title: String, body: String): NotificationRecord {
        val record = store.add(NotificationRecord(kind = kind, title = title, body = body))
        _records.value = store.list().reversed()
        return record
    }

    fun markAllRead() {
        store.markAllRead()
        _records.value = store.list().reversed()
    }

    fun clear() {
        store.clear()
        _records.value = emptyList()
    }
}
