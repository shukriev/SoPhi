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
        records.forEach { r ->
            // Only goal runs have a plan, so the counts are appended only when present —
            // a recurring run must not read as "replans=0".
            val plan = r.replans?.let { "  [replans=$it decompositions=${r.decompositions ?: 0}]" } ?: ""
            echo("${r.taskId}  ${r.outcome::class.simpleName}  ${r.summary}$plan")
        }
    }
}

class ScheduleListCommand : CliktCommand(name = "list") {
    private val scheduleDirStr: String by option("--schedule-dir").default(defaultScheduleDir().toString())
    override fun run() = ScheduleList(Path.of(scheduleDirStr)) { echo(it) }.run()
}

class ScheduleLogCommand : CliktCommand(name = "log") {
    private val scheduleDirStr: String by option("--schedule-dir").default(defaultScheduleDir().toString())
    private val taskId: String? by option("--task-id")
    private val tail: Int by option("--tail").int().default(20)
    override fun run() = ScheduleLog(Path.of(scheduleDirStr), taskId, tail) { echo(it) }.run()
}

class SchedulePauseCommand : CliktCommand(name = "pause") {
    private val scheduleDirStr: String by option("--schedule-dir").default(defaultScheduleDir().toString())
    private val id by argument()
    override fun run() = SchedulePause(Path.of(scheduleDirStr), id) { echo(it) }.run()
}

class ScheduleResumeCommand : CliktCommand(name = "resume") {
    private val scheduleDirStr: String by option("--schedule-dir").default(defaultScheduleDir().toString())
    private val id by argument()
    override fun run() = ScheduleResume(Path.of(scheduleDirStr), id) { echo(it) }.run()
}

class ScheduleRemoveCommand : CliktCommand(name = "remove") {
    private val scheduleDirStr: String by option("--schedule-dir").default(defaultScheduleDir().toString())
    private val id by argument()
    override fun run() = ScheduleRemove(Path.of(scheduleDirStr), id) { echo(it) }.run()
}
