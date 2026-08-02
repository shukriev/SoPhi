package dev.sophi.cli.goal

import dev.sophi.cli.LiveRegion
import dev.sophi.cli.ResponseRenderer
import dev.sophi.cli.streaming.StreamingTurnPresenter
import dev.sophi.core.agent.plan.Plan
import dev.sophi.core.agent.plan.PlanEvent
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.StepStatus
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole

/**
 * Consumes PlanEvents for one GoalController.run() invocation: owns terminal output, one
 * StreamingTurnPresenter per in-flight step, the anchor-session appends (Approach C from the
 * design — steps stay isolated in their own child sessions, but the anchor session gets a
 * structured record so /branch and follow-up turns see the episode), and PlanLog.
 */
class GoalRenderer(
    private val session: AgentSession,
    approvedPlan: Plan,
    private val liveRegion: LiveRegion,
    private val output: (String) -> Unit,
    private val planLog: PlanLog,
    tokenViewKey: Char,
    private val autoExitTokenView: Boolean
) {
    var currentPlan: Plan = approvedPlan; private set
    var lastStepOutput: String = ""; private set
    private var presenter = StreamingTurnPresenter(autoExitTokenView)

    init {
        // tokenViewKey is accepted for interface symmetry with TurnController/GoalController's
        // shared toggle-key configuration; the actual key match happens where GoalController
        // races input.awaitControlKeys(tokenViewKey, ...), not here.
        @Suppress("UNUSED_EXPRESSION") tokenViewKey
    }

    fun toggleTokenView() {
        presenter.toggleTokenView()
        render()
    }

    private fun render() = liveRegion.update(presenter.renderFrame())

    suspend fun handle(event: PlanEvent) {
        when (event) {
            is PlanEvent.PlanReady -> Unit // never arrives on the /goal path — GoalController supplies initialPlan
            is PlanEvent.StepAttempt -> {
                presenter = StreamingTurnPresenter(autoExitTokenView)
                val index = currentPlan.steps.indexOfFirst { it.id == event.step.id } + 1
                if (event.attempt == 1) {
                    output("\n▸ step $index/${currentPlan.steps.size} [${event.step.id}] ${event.step.instruction}")
                } else {
                    output("  ↑ retrying [${event.step.id}] with ${event.model}")
                }
                render()
            }
            is PlanEvent.StepTurn -> when (val rendered = presenter.feed(event.event)) {
                is StreamingTurnPresenter.Rendered.Cleared -> liveRegion.clear()
                is StreamingTurnPresenter.Rendered.Redraw -> render()
                is StreamingTurnPresenter.Rendered.ToolLine -> {
                    liveRegion.clear()
                    output(rendered.text)
                    render()
                }
                null -> Unit
            }
            is PlanEvent.Escalating ->
                output("  ~ confidence %.2f below threshold — escalating to %s".format(event.confidence, event.toModel))
            is PlanEvent.StepFinished -> {
                liveRegion.clear()
                presenter.reasoningText()?.let { output(ResponseRenderer.renderReasoning(it)) }
                val text = presenter.finalText()
                lastStepOutput = text
                val done = event.step.status == StepStatus.Done
                val confidenceText = event.step.confidence?.let { " (%.2f)".format(it) } ?: ""
                output("  ${if (done) "✓" else "✗"} ${event.step.id} ${if (done) "done" else "failed"}$confidenceText")
                if (text.isNotBlank()) output(ResponseRenderer.renderText(text))
                session.append(
                    EntryRole.ASSISTANT,
                    "[step ${event.step.id}] ${event.step.instruction}\n\n$text",
                    mapOf(
                        "replay" to "false", "planId" to currentPlan.id, "stepId" to event.step.id,
                        "planVersion" to event.planVersion.toString(), "status" to event.step.status.name,
                        "confidence" to (event.step.confidence?.toString() ?: "")
                    )
                )
            }
            is PlanEvent.Replanned -> {
                currentPlan = event.plan
                planLog.append(event.plan)
                output("  ↻ replanning after \"${event.event.reason}\" → plan v${event.plan.version} (${event.plan.steps.size} steps)")
                event.plan.steps.filter { it.status == StepStatus.Pending }.forEach {
                    output("     [${it.id}] ${it.instruction}")
                }
            }
        }
    }
}
