package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.cli.goal.GoalController
import dev.sophi.cli.goal.GoalRunResult
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.core.undo.FileRestorer
import dev.sophi.core.undo.RestorePlan
import dev.sophi.learning.LearningPlugin
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.MemoryPlugin
import dev.sophi.memory.MemoryView
import dev.sophi.memory.ProfileAction
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.skills.SkillRegistry
import java.nio.file.Path

class SlashHandler(
    private val sessionManager: SessionManager,
    private val compactor: ContextCompactor?,
    private val config: AgentConfig,
    private val learning: LearningPlugin? = null,
    private val scheduleDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule"),
    private val learningHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning"),
    private val memoryPlugin: MemoryPlugin? = null,
    private val skillRegistry: SkillRegistry = SkillRegistry(emptyMap()),
    private val provider: LLMProvider? = null,
    private val calendarProvider: CalendarProvider? = null,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    private val autoModeToggle: ToggleableConfirmationPolicy? = null,
    private val goalController: GoalController? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val planLog: PlanLog? = null,
    private val fileRestorer: FileRestorer? = null,
    private val auditSink: AuditSink? = null,
    private val permissions: dev.sophi.core.permissions.PermissionRules? = null,
    private val checkpoints: CheckpointStore? = null,
    /**
     * Total context window of `config.model`, in tokens. Optional here for the same reason
     * `provider` and `toolRegistry` are: a SlashHandler built without the full agent wiring
     * simply reports the affected commands as unavailable rather than guessing a value.
     */
    private val contextWindowTokens: Int? = null,
    private val liveRegion: LiveRegion = LiveRegion(StringBuilder()) { 80 },
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    private val input: InputSource = NoOpInputSource,
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
                if (arg != null && "--files" in arg.split(" ")) return handleCheckoutFiles(arg, session)
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
            "undo" -> withRestorer(session) { restorer, current ->
                restorer.plan(current)?.let { confirmAndApply(restorer, current, it) } ?: output("Nothing to undo.")
            }
            "revert" -> withRestorer(session) { restorer, current ->
                if (arg.isNullOrEmpty()) output("Usage: /revert <entry-id>")
                else try {
                    restorer.plan(current, arg)?.let { confirmAndApply(restorer, current, it) } ?: output("Nothing to undo.")
                } catch (e: IllegalArgumentException) {
                    output("Error: ${e.message}")
                }
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
            "schedule" -> { handleSchedule(arg); session }
            "calendar" -> { handleCalendar(arg); session }
            "feedback" -> {
                if (learning == null) output("Learning is not enabled.") else handleFeedback(arg)
                session
            }
            "lessons" -> {
                if (learning == null) output("Learning is not enabled.") else handleLessons(arg)
                session
            }
            "memory" -> {
                val palace = memoryPlugin?.technique as? JanesPalace
                if (palace == null) output("Memory is not enabled.") else handleMemory(palace, arg)
                session
            }
            "skill" -> { handleSkill(arg, session); session }
            "plan" -> handlePlan(arg, session)
            "goal" -> {
                val controller = goalController
                if (controller == null) {
                    output("Goal mode is not available.")
                    session
                } else {
                    when (val result = controller.run(session, arg)) {
                        is GoalRunResult.Ran -> result.session
                        is GoalRunResult.Declined -> result.session
                    }
                }
            }
            "auto" -> { handleAuto(); session }
            "permissions" -> { handlePermissions(arg); session }
            else -> {
                output(
                    "Unknown command: /$cmd  Available: /list /branch /checkout /undo /revert /compact /good /bad " +
                        "/schedule /calendar /feedback /lessons /memory /skill /plan /goal /auto /permissions"
                )
                session
            }
        }
    }

    /** Reloads the session (turns are saved by the runtime, so [session] may be stale) and runs [block] on it. */
    private suspend fun withRestorer(session: AgentSession, block: suspend (FileRestorer, AgentSession) -> Unit): AgentSession {
        val restorer = fileRestorer
        if (restorer == null) { output("Undo is not available."); return session }
        val current = sessionManager.load(session.id)
        block(restorer, current)
        return current
    }

    private suspend fun handleCheckoutFiles(arg: String, session: AgentSession): AgentSession =
        withRestorer(session) { restorer, current ->
            val id = arg.split(" ").firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
            if (id == null) { output("Usage: /checkout <entry-id> [--files]"); return@withRestorer }
            try {
                val branch = current.branch()
                val at = branch.indexOfFirst { it.id == id }
                // The plan has to come first: after the checkout the later rounds are off the branch.
                val plan = branch.getOrNull(at + 1)?.takeIf { at >= 0 }?.let { restorer.plan(current, it.id) }
                current.checkout(id)
                output("Checked out entry $id")
                if (plan != null) confirmAndApply(restorer, current, plan)
            } catch (e: Exception) {
                output("Error: ${e.message}")
            }
        }

    private suspend fun confirmAndApply(restorer: FileRestorer, session: AgentSession, plan: RestorePlan) {
        output(plan.diffStat.ifBlank { null }?.let { "Restoring will change:\n$it" } ?: "No workspace file changes.")
        plan.moves.forEach { output("  restore ${it.from} (from ${it.to})") }
        plan.warnings.forEach { output("  can't undo: $it") }
        output("Restore these files? (y/n)")
        if (!input.awaitYesNo()) { output("Left as is."); return }
        try {
            restorer.apply(session, plan).forEach { output(it) }
            output("Restored.")
        } catch (e: IllegalStateException) {
            output("Error: ${e.message}")
        }
        // Saved even when the restore threw: the moves were already reversed.
        sessionManager.save(session)
    }

    private fun handleSchedule(arg: String?) {
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0].lowercase()
        val subArg = parts.getOrNull(1)?.trim()
        when (sub) {
            "list" -> ScheduleList(scheduleDir, output).run()
            "log" -> ScheduleLog(scheduleDir, subArg, 20, output).run()
            "pause" -> if (subArg.isNullOrEmpty()) output("Usage: /schedule pause <task-id>")
                else SchedulePause(scheduleDir, subArg, output).run()
            "resume" -> if (subArg.isNullOrEmpty()) output("Usage: /schedule resume <task-id>")
                else ScheduleResume(scheduleDir, subArg, output).run()
            "remove" -> if (subArg.isNullOrEmpty()) output("Usage: /schedule remove <task-id>")
                else ScheduleRemove(scheduleDir, subArg, output).run()
            else -> output("Unknown /schedule subcommand: $sub  Available: list log pause resume remove")
        }
    }

    private suspend fun handleCalendar(arg: String?) {
        val calProvider = calendarProvider
        val llmProvider = provider
        val window = contextWindowTokens
        if (calProvider == null || llmProvider == null || window == null) {
            output("Calendar is not enabled."); return
        }
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0].lowercase()
        val subArg = parts.getOrNull(1)?.trim()
        when (sub) {
            "list" -> CalendarList(calProvider, subArg?.toIntOrNull() ?: 7, output).run()
            "get" -> {
                if (subArg.isNullOrEmpty()) output("Usage: /calendar get <event-id> [calendar-id]")
                else subArg.split(" ", limit = 2).let { CalendarGet(calProvider, it[0], it.getOrNull(1), output).run() }
            }
            "delete" -> {
                if (subArg.isNullOrEmpty()) output("Usage: /calendar delete <event-id> [calendar-id]")
                else subArg.split(" ", limit = 2).let { CalendarDelete(calProvider, it[0], it.getOrNull(1), output).run() }
            }
            "calendars" -> CalendarCalendars(calProvider, output).run()
            "create" -> {
                if (subArg.isNullOrEmpty()) output("Usage: /calendar create <description>")
                else CalendarCreate(
                    llmProvider, calProvider, sessionManager, confirmationPolicy, config,
                    window, subArg, auditSink, permissions, output
                ).run()
            }
            else -> output("Unknown /calendar subcommand: $sub  Available: list get delete calendars create")
        }
    }

    private fun handleFeedback(arg: String?) {
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0].lowercase()
        val subArg = parts.getOrNull(1)?.trim()
        when (sub) {
            "list" -> FeedbackList(learningHome, subArg ?: System.getProperty("user.dir"), output).run()
            "delete" -> if (subArg.isNullOrEmpty()) output("Usage: /feedback delete <id>")
                else FeedbackDelete(learningHome, subArg, output).run()
            else -> output("Unknown /feedback subcommand: $sub  Available: list delete")
        }
    }

    private fun handleLessons(arg: String?) {
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0].lowercase()
        val subArg = parts.getOrNull(1)?.trim()
        when (sub) {
            "list" -> LessonsList(learningHome, subArg ?: System.getProperty("user.dir"), output).run()
            "archive" -> if (subArg.isNullOrEmpty()) output("Usage: /lessons archive <id>")
                else LessonsArchive(learningHome, subArg, output).run()
            else -> output("Unknown /lessons subcommand: $sub  Available: list archive")
        }
    }

    private fun handleSkill(arg: String?, session: AgentSession) {
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0]
        if (sub == "list") {
            if (skillRegistry.all().isEmpty()) output("No skills installed.")
            else skillRegistry.all().forEach { (id, s) -> output("$id: ${s.metadata.description}") }
            return
        }
        val skill = skillRegistry.get(sub)
        if (skill == null) {
            output("Unknown skill: $sub. Run /skill list to see available skills.")
        } else {
            session.append(EntryRole.TOOL_RESULT, skill.body)
            output("Injected skill: $sub")
        }
    }

    private suspend fun handlePlan(arg: String?, session: AgentSession): AgentSession {
        if (arg.isNullOrBlank()) {
            output("Usage: /plan <goal>")
            return session
        }
        val registry = toolRegistry
        val llm = provider
        val window = contextWindowTokens
        if (registry == null || llm == null || window == null) {
            output("Planning is not available (no tools configured).")
            return session
        }
        return PlanCommand(
            provider = llm, registry = registry, sessionManager = sessionManager, config = config,
            contextWindowTokens = window, confirmationPolicy = confirmationPolicy, planLog = planLog,
            onEvent = onEvent, liveRegion = liveRegion, input = input, checkpoints = checkpoints, audit = auditSink, permissions = permissions, echo = output
        ).run(arg, session)
    }

    private fun handlePermissions(arg: String?) {
        val rules = permissions
        if (rules == null) { output("Permission rules are off."); return }
        if (arg?.trim() == "trust") {
            val n = rules.untrusted.size
            try { rules.trustProject() } catch (e: Exception) { output("Could not save trust: ${e.message}"); return }
            output("Trusted $n project allow rule(s).")
            return
        }
        fun line(r: dev.sophi.core.permissions.PermissionRule, where: String) =
            "${r.effect.name.lowercase()} ${r.tool} ${r.pattern ?: "(any call)"}  ($where)"
        val global = rules.global
        val lines = rules.active.map { line(it, if (it in global) "global" else "project") } +
            rules.untrusted.map { line(it, "project, untrusted — /permissions trust") }
        output(if (lines.isEmpty()) "No permission rules." else lines.joinToString("\n"))
    }

    private fun handleAuto() {
        val toggle = autoModeToggle
        if (toggle == null) {
            output("Auto mode is not available in this session.")
            return
        }
        toggle.autoModeEnabled = !toggle.autoModeEnabled
        output("Auto mode: ${if (toggle.autoModeEnabled) "on" else "off"}")
    }

    private fun renderMemoryView(v: MemoryView): String {
        val m = v.metadata
        return "[${v.id}] (${m["room"]}, sal ${m["salience"]}, pri ${m["priority"]}, ${m["ageDays"]}d, " +
            "${m["sensitivity"]}, ${m["state"]}) ${v.text}"
    }

    private fun handleMemory(palace: JanesPalace, arg: String?) {
        val parts = (arg ?: "list").trim().ifEmpty { "list" }.split(" ", limit = 2)
        val sub = parts[0].lowercase()
        val subArg = parts.getOrNull(1)?.trim()
        when (sub) {
            "list" -> {
                // /memory list [room] [--provenance=X] [--source=Y] — the bare-room form predates
                // the flags and must keep working, so the room is simply the first non-flag token.
                val tokens = subArg?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
                val views = palace.browse(BrowseFilter(
                    room = tokens.firstOrNull { !it.startsWith("--") },
                    provenance = tokens.firstOrNull { it.startsWith("--provenance=") }?.substringAfter("="),
                    sourceSessionId = tokens.firstOrNull { it.startsWith("--source=") }?.substringAfter("=")
                ))
                if (views.isEmpty()) output("(no memories)") else views.forEach { output(renderMemoryView(it)) }
            }
            "show" -> {
                if (subArg.isNullOrEmpty()) { output("Usage: /memory show <id>"); return }
                val v = palace.browse(BrowseFilter(includeHidden = true)).firstOrNull { it.id == subArg }
                if (v == null) output("Not found: $subArg")
                else {
                    output(renderMemoryView(v))
                    v.metadata.forEach { (k, value) -> output("  $k = $value") }
                }
            }
            "threads" -> {
                val threads = palace.threads()
                if (threads.isEmpty()) output("(no threads)")
                else threads.forEach { (label, texts) -> output("[$label] " + texts.joinToString(" -> ")) }
            }
            "profile" -> {
                val actionParts = subArg?.split(" ", limit = 3)
                when (actionParts?.getOrNull(0)) {
                    null -> {
                        val views = palace.profileView()
                        if (views.isEmpty()) output("(empty profile)")
                        else views.forEach { output("${it.path} = ${it.value} (%.2f)".format(it.confidence)) }
                    }
                    "confirm" -> {
                        val p = actionParts.getOrNull(1)
                        if (p == null) output("Usage: /memory profile confirm <path>")
                        else output(if (palace.updateProfile(ProfileAction.Confirm(p))) "Confirmed." else "No such attribute.")
                    }
                    "correct" -> {
                        val p = actionParts.getOrNull(1)
                        val v = actionParts.getOrNull(2)
                        if (p == null || v == null) output("Usage: /memory profile correct <path> <value>")
                        else output(if (palace.updateProfile(ProfileAction.Correct(p, v))) "Corrected." else "No such attribute.")
                    }
                    "delete" -> {
                        val p = actionParts.getOrNull(1)
                        if (p == null) output("Usage: /memory profile delete <path>")
                        else output(if (palace.updateProfile(ProfileAction.Delete(p))) "Deleted." else "No such attribute.")
                    }
                    else -> output("Unknown action: ${actionParts[0]} (use confirm|correct|delete)")
                }
            }
            "why" -> output(palace.explainLastRecall() ?: "(no recall recorded yet)")
            else -> output("Unknown /memory subcommand: $sub  Available: list show threads profile why")
        }
    }
}
