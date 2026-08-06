package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
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
    private val toolRegistry: ToolRegistry? = null,
    /**
     * Total context window of `config.model`, in tokens. Optional here for the same reason
     * `provider` and `toolRegistry` are: a SlashHandler built without the full agent wiring
     * simply reports the affected commands as unavailable rather than guessing a value.
     */
    private val contextWindowTokens: Int? = null,
    private val liveRegion: LiveRegion = LiveRegion(StringBuilder()) { 80 },
    private val onEvent: suspend (TurnEvent) -> Unit = {},
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
            "auto" -> { handleAuto(); session }
            else -> {
                output(
                    "Unknown command: /$cmd  Available: /list /branch /checkout /compact /good /bad " +
                        "/schedule /calendar /feedback /lessons /memory /skill /plan /auto"
                )
                session
            }
        }
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
                    window, subArg, output
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
        return PlanCommand(llm, registry, sessionManager, config, window, confirmationPolicy, null, onEvent, liveRegion, output)
            .run(arg, session)
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
                val views = palace.browse(BrowseFilter(room = subArg))
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
