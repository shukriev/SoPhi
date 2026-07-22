package dev.sophi.schedule.store

import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.Trigger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class TaskStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<ScheduledTask> = synchronized(lock) { readAll() }

    fun get(id: String): ScheduledTask? = list().find { it.id == id }

    fun add(task: ScheduledTask): ScheduledTask = synchronized(lock) {
        val withSchedule = task.copy(nextRunAtMs = initialNextRunAt(task.trigger))
        writeAll(readAll() + withSchedule)
        withSchedule
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val all = readAll()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val task = all[idx]
        val updated = if (enabled) {
            task.copy(enabled = true, nextRunAtMs = initialNextRunAt(task.trigger))
        } else {
            task.copy(enabled = false)
        }
        writeAll(all.toMutableList().also { it[idx] = updated })
        true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val all = readAll()
        if (all.none { it.id == id }) return false
        writeAll(all.filterNot { it.id == id })
        true
    }

    fun recordRun(id: String, finishedAtMs: Long): Boolean = synchronized(lock) {
        val all = readAll()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val task = all[idx]
        val updated = task.copy(
            lastRunAtMs = finishedAtMs,
            nextRunAtMs = rescheduleNextRunAt(task.trigger, finishedAtMs),
            iterationCount = task.iterationCount + 1,
            enabled = if (task.trigger is Trigger.Once) false else task.enabled
        )
        writeAll(all.toMutableList().also { it[idx] = updated })
        true
    }

    private fun initialNextRunAt(trigger: Trigger): Long? = when (trigger) {
        is Trigger.Interval -> System.currentTimeMillis() + trigger.everySeconds * 1000
        is Trigger.Once -> trigger.atMs
        is Trigger.Manual -> null
    }

    private fun rescheduleNextRunAt(trigger: Trigger, finishedAtMs: Long): Long? = when (trigger) {
        is Trigger.Interval -> finishedAtMs + trigger.everySeconds * 1000
        is Trigger.Once -> null
        is Trigger.Manual -> null
    }

    private fun readAll(): List<ScheduledTask> {
        if (!Files.exists(path)) return emptyList()
        val text = Files.readString(path)
        if (text.isBlank()) return emptyList()
        return json.decodeFromString<List<ScheduledTask>>(text)
    }

    private fun writeAll(tasks: List<ScheduledTask>) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString<List<ScheduledTask>>(tasks))
    }
}
