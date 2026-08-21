package dev.sophi.schedule.tools

import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.RuleVerdict
import dev.sophi.core.tools.Tool
import dev.sophi.schedule.model.CronSchedules
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

private const val TOOL_NAME = "manage_scheduled_task"

@Serializable
private data class ManageTaskArgs(
    val action: String,
    val name: String? = null,
    val prompt: String? = null,
    @SerialName("trigger_type") val triggerType: String? = null,
    @SerialName("every_seconds") val everySeconds: Long? = null,
    @SerialName("at") val at: String? = null,
    @SerialName("cron_expression") val cronExpression: String? = null,
    val mode: String? = null,
    @SerialName("stop_condition_type") val stopConditionType: String? = null,
    @SerialName("shell_command") val shellCommand: String? = null,
    @SerialName("max_iterations") val maxIterations: Int? = null,
    @SerialName("tool_grants") val toolGrants: List<String>? = null,
    @SerialName("task_id") val taskId: String? = null
)

class ScheduleTaskTool(private val store: TaskStore, private val runLog: RunLog) : Tool {
    override val name = TOOL_NAME
    override fun riskLevel(argumentsJson: String): RiskLevel {
        val args = runCatching { json.decodeFromString(ManageTaskArgs.serializer(), argumentsJson) }.getOrNull()
            ?: return RiskLevel.DESTRUCTIVE
        val grantingPower = args.action in setOf("create", "update") && args.toolGrants?.isNotEmpty() == true
        return if (grantingPower) RiskLevel.DESTRUCTIVE else RiskLevel.SAFE
    }
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val args = runCatching { json.decodeFromString(ManageTaskArgs.serializer(), argumentsJson) }.getOrNull()
            ?: return RuleVerdict.HIGH_RISK
        val grantingPower = args.action in setOf("create", "update") && args.toolGrants?.isNotEmpty() == true
        return if (grantingPower) RuleVerdict.HIGH_RISK else RuleVerdict.LOW_RISK
    }
    override val description =
        "Create, list, update, pause, resume, or remove a scheduled or goal-based background task, " +
            "and inspect its run history (outcome + duration of each past run). " +
            "Recurring tasks fire on an interval with no stop condition (e.g. hourly monitoring). " +
            "Goal tasks repeat turns until an LLM-judged or shell-checked condition is met, up to max_iterations. " +
            "at (trigger_type=once) is an ISO-8601 local date-time you must compute yourself — if the request uses " +
            "a relative time (\"tomorrow\", \"in an hour\"), call get_current_datetime first; never guess it. " +
            "cron_expression (trigger_type=cron) is standard 5-field Unix cron syntax resolved in the local timezone."
    override val parametersJson = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["create","list","update","pause","resume","remove","runs"]},
          "name":{"type":"string"},
          "prompt":{"type":"string","description":"Instruction given to the agent each time this task runs"},
          "trigger_type":{"type":"string","enum":["interval","once","cron","manual"]},
          "every_seconds":{"type":"integer","description":"Required when trigger_type=interval"},
          "at":{"type":"string","description":"ISO-8601 local date-time, e.g. 2026-07-27T12:00:00; required when trigger_type=once"},
          "cron_expression":{"type":"string","description":"Required when trigger_type=cron. Standard 5-field Unix cron syntax (minute hour day-of-month month day-of-week), e.g. \"0 9 * * *\" for 9am daily. Resolved in the local system timezone."},
          "mode":{"type":"string","enum":["recurring","goal"]},
          "stop_condition_type":{"type":"string","enum":["llm_judged","shell_check"],"description":"Required when mode=goal"},
          "shell_command":{"type":"string","description":"Required when stop_condition_type=shell_check"},
          "max_iterations":{"type":"integer","description":"Required when mode=goal"},
          "tool_grants":{"type":"array","items":{"type":"string"},"description":"Tool names this task may call unattended without confirmation, e.g. fetch_url"},
          "task_id":{"type":"string","description":"Required for update, pause, resume, remove; optional filter for runs"}
        },"required":["action"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseLocalDateTime(s: String): Long? =
        runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString(ManageTaskArgs.serializer(), argumentsJson)
        return when (args.action) {
            "create" -> create(args)
            "list" -> list()
            "update" -> update(args)
            "pause" -> setEnabled(args, false)
            "resume" -> setEnabled(args, true)
            "remove" -> remove(args)
            "runs" -> runs(args)
            else -> "Error: unknown action '${args.action}'. Expected create, list, update, pause, resume, remove, or runs."
        }
    }

    private fun create(args: ManageTaskArgs): String {
        val name = args.name ?: return "Error: 'name' is required for action=create"
        val prompt = args.prompt ?: return "Error: 'prompt' is required for action=create"
        val trigger = when (args.triggerType) {
            "interval" -> args.everySeconds?.let { Trigger.Interval(it) }
                ?: return "Error: 'every_seconds' is required for trigger_type=interval"
            "once" -> {
                val atStr = args.at ?: return "Error: 'at' is required for trigger_type=once"
                val atMs = parseLocalDateTime(atStr)
                    ?: return "Error: invalid 'at' datetime '$atStr', expected ISO-8601 like 2026-07-27T12:00:00"
                Trigger.Once(atMs)
            }
            "cron" -> args.cronExpression?.let { expr ->
                CronSchedules.validate(expr)?.let { error -> return "Error: invalid cron expression: $error" }
                Trigger.Cron(expr)
            } ?: return "Error: 'cron_expression' is required for trigger_type=cron"
            "manual" -> Trigger.Manual
            else -> return "Error: 'trigger_type' must be interval, once, cron, or manual"
        }
        val mode = when (args.mode) {
            "recurring" -> TaskMode.Recurring
            "goal" -> {
                val maxIterations = args.maxIterations
                    ?: return "Error: 'max_iterations' is required for mode=goal"
                val stopCondition = when (args.stopConditionType) {
                    "llm_judged" -> StopCondition.LlmJudged
                    "shell_check" -> args.shellCommand?.let { StopCondition.ShellCheck(it) }
                        ?: return "Error: 'shell_command' is required for stop_condition_type=shell_check"
                    else -> return "Error: 'stop_condition_type' must be llm_judged or shell_check"
                }
                TaskMode.Goal(stopCondition, maxIterations)
            }
            else -> return "Error: 'mode' must be recurring or goal"
        }
        val task = store.add(
            ScheduledTask(
                name = name,
                trigger = trigger,
                mode = mode,
                prompt = prompt,
                toolGrants = args.toolGrants?.toSet() ?: emptySet()
            )
        )
        return "Created task ${task.id} (${task.name})"
    }

    private fun update(args: ManageTaskArgs): String {
        val id = args.taskId ?: return "Error: 'task_id' is required for action=update"
        if (store.get(id) == null) return "Error: no task found with id $id"

        val newTrigger: Trigger? = when (args.triggerType) {
            null -> null
            "interval" -> args.everySeconds?.let { Trigger.Interval(it) }
                ?: return "Error: 'every_seconds' is required when trigger_type=interval"
            "once" -> {
                val atStr = args.at ?: return "Error: 'at' is required when trigger_type=once"
                val atMs = parseLocalDateTime(atStr)
                    ?: return "Error: invalid 'at' datetime '$atStr', expected ISO-8601 like 2026-07-27T12:00:00"
                Trigger.Once(atMs)
            }
            "cron" -> args.cronExpression?.let { expr ->
                CronSchedules.validate(expr)?.let { error -> return "Error: invalid cron expression: $error" }
                Trigger.Cron(expr)
            } ?: return "Error: 'cron_expression' is required when trigger_type=cron"
            "manual" -> Trigger.Manual
            else -> return "Error: 'trigger_type' must be interval, once, cron, or manual"
        }

        val updated = store.update(id) { task ->
            task.copy(
                name = args.name ?: task.name,
                prompt = args.prompt ?: task.prompt,
                trigger = newTrigger ?: task.trigger,
                toolGrants = args.toolGrants?.toSet() ?: task.toolGrants
            )
        }
        return if (updated) "Updated $id" else "Error: no task found with id $id"
    }

    private fun renderTrigger(trigger: Trigger): String = when (trigger) {
        is Trigger.Interval -> "every ${trigger.everySeconds}s"
        is Trigger.Once -> "once at epoch ${trigger.atMs}ms"
        is Trigger.Cron -> "cron '${trigger.expression}'"
        is Trigger.Manual -> "manual"
    }

    private fun list(): String {
        val tasks = store.list()
        if (tasks.isEmpty()) return "No scheduled tasks."
        return tasks.joinToString("\n") {
            "${it.id}  ${if (it.enabled) "enabled" else "paused"}  ${it.name}  " +
                "(${it.mode::class.simpleName}, ${renderTrigger(it.trigger)})"
        }
    }

    private fun setEnabled(args: ManageTaskArgs, enabled: Boolean): String {
        val id = args.taskId ?: return "Error: 'task_id' is required for action=${args.action}"
        return if (store.setEnabled(id, enabled)) {
            "${if (enabled) "Resumed" else "Paused"} $id"
        } else {
            "Error: no task found with id $id"
        }
    }

    private fun remove(args: ManageTaskArgs): String {
        val id = args.taskId ?: return "Error: 'task_id' is required for action=remove"
        return if (store.remove(id)) "Removed $id" else "Error: no task found with id $id"
    }

    private fun runs(args: ManageTaskArgs): String {
        val records = (args.taskId?.let { runLog.forTask(it) } ?: runLog.readAll()).takeLast(20)
        if (records.isEmpty()) return "No run history."
        return records.joinToString("\n") { r ->
            val durationSec = (r.finishedAtMs - r.startedAtMs) / 1000.0
            "${r.taskId}  ${r.outcome::class.simpleName}  " +
                String.format(Locale.ROOT, "%.1fs", durationSec) + "  ${r.summary}"
        }
    }
}
