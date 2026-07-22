package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import java.nio.file.Path

class ScheduleList(private val home: Path, private val echo: (String) -> Unit) {
    fun run() {
        val tasks = TaskStore(home.resolve("tasks.json")).list()
        if (tasks.isEmpty()) { echo("No scheduled tasks."); return }
        tasks.forEach {
            echo("${it.id}  ${if (it.enabled) "enabled" else "paused"}  ${it.name}  (${it.mode::class.simpleName})")
        }
    }
}

class SchedulePause(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() {
        if (TaskStore(home.resolve("tasks.json")).setEnabled(id, false)) echo("Paused $id")
        else echo("No task found with id $id")
    }
}

class ScheduleResume(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() {
        if (TaskStore(home.resolve("tasks.json")).setEnabled(id, true)) echo("Resumed $id")
        else echo("No task found with id $id")
    }
}

class ScheduleRemove(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() {
        if (TaskStore(home.resolve("tasks.json")).remove(id)) echo("Removed $id")
        else echo("No task found with id $id")
    }
}

class ScheduleLog(
    private val home: Path,
    private val taskId: String?,
    private val tail: Int,
    private val echo: (String) -> Unit
) {
    fun run() {
        val log = RunLog(home.resolve("runs.jsonl"))
        val records = (taskId?.let { log.forTask(it) } ?: log.readAll()).takeLast(tail)
        if (records.isEmpty()) { echo("No run history."); return }
        records.forEach { echo("${it.taskId}  ${it.outcome::class.simpleName}  ${it.summary}") }
    }
}

class ScheduleListCommand : CliktCommand(name = "list") {
    override fun run() = ScheduleList(defaultScheduleDir()) { echo(it) }.run()
}

class ScheduleLogCommand : CliktCommand(name = "log") {
    private val taskId: String? by option("--task-id")
    private val tail: Int by option("--tail").int().default(20)
    override fun run() = ScheduleLog(defaultScheduleDir(), taskId, tail) { echo(it) }.run()
}

class SchedulePauseCommand : CliktCommand(name = "pause") {
    private val id by argument()
    override fun run() = SchedulePause(defaultScheduleDir(), id) { echo(it) }.run()
}

class ScheduleResumeCommand : CliktCommand(name = "resume") {
    private val id by argument()
    override fun run() = ScheduleResume(defaultScheduleDir(), id) { echo(it) }.run()
}

class ScheduleRemoveCommand : CliktCommand(name = "remove") {
    private val id by argument()
    override fun run() = ScheduleRemove(defaultScheduleDir(), id) { echo(it) }.run()
}
