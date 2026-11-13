package dev.sophi.sdk.rpc

import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.TurnInbox
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.audit.noteDecision
import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.dryrun.listLine
import dev.sophi.core.dryrun.replayReport
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.sdk.GoalStatus
import dev.sophi.sdk.SophiRuntime
import dev.sophi.sdk.runGoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The approver for an RPC client: emits `confirmation_requested` and waits for a `confirm` per call,
 * with no timeout. Once [closeInput] is called nobody can answer, so every pending and later call is denied.
 */
class RpcConfirmations(private val emit: suspend (RpcEvent) -> Unit) : ConfirmationPolicy {
    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var closed = false

    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        // Registered before the request goes out, so an answer can never arrive first.
        val waits = synchronized(lock) {
            requests.associate { r ->
                r.callId to CompletableDeferred<Boolean>().also { if (closed) it.complete(false) else pending[r.callId] = it }
            }
        }
        try {
            emit(RpcEvent.ConfirmationRequested(requests))
            return waits.mapValues { (callId, answer) ->
                answer.await().also { noteDecision(callId, "rpc"); emit(RpcEvent.ConfirmationResolved(callId, it)) }
            }
        } finally {
            synchronized(lock) { waits.keys.forEach { pending.remove(it) } }
        }
    }

    /** False when no call [callId] is waiting. */
    fun answer(callId: String, approved: Boolean): Boolean = synchronized(lock) { pending.remove(callId) }?.complete(approved) == true

    fun closeInput() = synchronized(lock) {
        closed = true
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }
}

/**
 * Drives one [session] from JSON-line commands, writing one event per line to [out]. Commands are read
 * while a turn runs, so `steer`, `abort` and `confirm` work mid-turn. [switchModel] returns an error
 * text, or null when it switched. The [confirmations] must be the runtime's confirmation policy.
 */
class RpcServer(
    private val runtime: SophiRuntime,
    @Volatile private var session: AgentSession,
    private val confirmations: RpcConfirmations,
    private val memory: MemoryStatus,
    private val switchModel: suspend (String) -> String?,
    private val out: (String) -> Unit,
    private val planLog: PlanLog? = null,
) {
    private val writes = Mutex()
    private val inbox = TurnInbox()
    private val lock = Any()
    /** The running turn or goal, with any follow-ups it picks up; null when idle. Guarded by [lock]. */
    private var turn: Job? = null

    /** Writes [e] as one line. Confirmation requests carry tool arguments, so they are masked here. */
    suspend fun emit(e: RpcEvent) {
        val masked = if (e !is RpcEvent.ConfirmationRequested) e else RpcEvent.ConfirmationRequested(e.requests.map {
            it.copy(argumentsJson = runtime.maskSecrets(it.argumentsJson), preview = it.preview?.let(runtime::maskSecrets))
        })
        writes.withLock { out(encodeRpcEvent(masked)) }
    }

    val sessionId: String get() = session.id

    /** Session end and start work [run] still owes, in order; it finishes all of it before `exited`. */
    private val lifecycle = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Done once the current session's start work (and the end work queued before it) has run; turns wait for it. */
    @Volatile private var started: Job = Job().apply { complete() }

    /**
     * Swaps in a fresh session, made as [SophiRuntime.newSession] makes one, and returns its id; null while a
     * turn runs. The hooks run in the background, in order (callers hold their own locks, and the end work may
     * ask the model): [SophiRuntime.recordSessionEnd] for the replaced session if it has any entries, then
     * [SophiRuntime.sessionStarted] for the new one, which the session's first turn waits for.
     * The session current at exit is the host's to end.
     */
    fun newSession(): String? = synchronized(lock) {
        if (turn != null) return null
        val old = session
        session = runtime.createSession()
        val id = session.id
        val done = Job()
        if (old.entries.isNotEmpty()) lifecycle.trySend { runtime.recordSessionEnd(old.id) }
        // ponytail: after run() has returned the channel is closed and this work is dropped; no turn can wait on it then.
        lifecycle.trySend { try { runtime.sessionStarted(id) } finally { done.complete() } }
        started = done
        id
    }

    /** Emits `ready`, serves [input] until it closes, lets the running turn and its follow-ups finish, emits `exited`. */
    suspend fun run(input: ReceiveChannel<String>) = coroutineScope {
        emit(RpcEvent.Ready(session.id, runtime.config.model, runtime.provider?.name ?: "", memory))
        val hooks = launch { for (work in lifecycle) work() }
        for (line in input) {
            if (line.isBlank()) continue
            decodeRpcCommand(line).fold({ handle(it) }, { emit(RpcEvent.Error("bad command: ${it.message}")) })
        }
        confirmations.closeInput()
        synchronized(lock) { turn }?.join()
        lifecycle.close()
        hooks.join()
        emit(RpcEvent.Exited)
    }

    private suspend fun CoroutineScope.handle(cmd: RpcCommand) {
        when (cmd) {
            is RpcCommand.Prompt -> queueOrStart(cmd.text, steer = false)
            is RpcCommand.FollowUp -> queueOrStart(cmd.text, steer = false)
            is RpcCommand.Steer -> queueOrStart(cmd.text, steer = true)
            // Joined, so a command read next sees the turn gone instead of queueing behind it.
            is RpcCommand.Abort -> synchronized(lock) { turn }?.cancelAndJoin() ?: emit(RpcEvent.Error("no turn is running", cmd.id))
            is RpcCommand.Confirm ->
                if (!confirmations.answer(cmd.callId, cmd.approved)) emit(RpcEvent.Error("no pending confirmation ${cmd.callId}", cmd.id))
            is RpcCommand.Goal -> if (!startIfIdle { goal(cmd.text, cmd.check) }) emit(RpcEvent.Error("a turn is running", cmd.id))
            is RpcCommand.Compact -> sessionCommand(cmd.id, "compact") {
                session = runtime.compact(session)
                "${session.branch().size} entries"
            }
            is RpcCommand.Branch -> sessionCommand(cmd.id, "branch") {
                runtime.branch(session).withIndex().joinToString("\n") { (i, e) -> "${i + 1}. [${e.role}] ${e.id}  ${e.content.take(60)}" }
            }
            is RpcCommand.Checkout -> sessionCommand(cmd.id, "checkout") {
                runtime.checkout(session, cmd.entryId, cmd.files).joinToString("\n")
            }
            is RpcCommand.SetModel -> sessionCommand(cmd.id, "set_model") {
                switchModel(cmd.model)?.let { throw IllegalArgumentException(it) }
                cmd.model
            }
            is RpcCommand.Plans -> sessionCommand(cmd.id, "plans") {
                runtime.plans().joinToString("\n") { it.listLine() }.ifEmpty { "No plans." }
            }
            // Approved here, then replayed as a turn, so `abort` stops it and other commands see a turn running.
            is RpcCommand.ApprovePlan -> {
                if (synchronized(lock) { turn != null }) return emit(RpcEvent.Error("a turn is running", cmd.id))
                val plan = runCatching {
                    val plan = runtime.plan(cmd.planId) ?: throw IllegalArgumentException("No plan ${cmd.planId}")
                    runtime.approvePlan(plan.id, plan.dropped)
                }.getOrElse { return emit(RpcEvent.Error(it.message ?: it.toString(), cmd.id)) }
                startIfIdle { replay(plan.id, plan.sessionId) }
            }
            is RpcCommand.CancelPlan -> sessionCommand(cmd.id, "cancel_plan") {
                runtime.cancelPlan(cmd.planId)
                "Cancelled; nothing ran."
            }
        }
    }

    /** Runs [block] when no turn is running and acks its result; any failure is an `error`. */
    private suspend fun sessionCommand(id: String?, command: String, block: suspend () -> String) {
        if (synchronized(lock) { turn != null }) return emit(RpcEvent.Error("a turn is running", id))
        runCatching { block() }.fold(
            { emit(RpcEvent.Ack(id, command, it)) },
            { emit(RpcEvent.Error(it.message ?: it.toString(), id)) },
        )
    }

    private fun CoroutineScope.queueOrStart(text: String, steer: Boolean) {
        startIfIdle(busy = { if (steer) inbox.steer(text) else inbox.followUp(text) }) { turnOnce(text) }
    }

    /**
     * Starts [first] and then whatever was queued behind it; if a turn is running, calls [busy] instead and
     * returns false. Queueing, taking the next message and going idle all happen under [lock], so a message
     * queued just as the turn ends still runs. An abort drops what was queued. Each turn returns its
     * `turn_ended` unsent; [endTurn] sends it only after deciding whether to go idle.
     */
    private fun CoroutineScope.startIfIdle(busy: () -> Unit = {}, first: suspend () -> RpcEvent.TurnEnded): Boolean = synchronized(lock) {
        if (turn != null) { busy(); return false }
        // Atomic: an abort before the body runs still goes through it, so the turn ends and frees [turn].
        // The job only reads [turn] under [lock], which is held here until [turn] is set.
        val job = launch(start = CoroutineStart.ATOMIC) {
            try {
                var ended = first()
                while (true) {
                    val next = withContext(NonCancellable) { endTurn(ended) } ?: break
                    ended = turnOnce(next)
                }
            } catch (e: CancellationException) {
                synchronized(lock) { inbox.drainAll() }
                throw e
            } finally {
                // Only this job's own slot: once it went idle above, a newer turn may hold [turn].
                synchronized(lock) { if (turn === coroutineContext.job) turn = null }
            }
        }
        turn = job
        true
    }

    /**
     * Takes the next queued message, or goes idle when there is none, then writes [ended]. Both happen under
     * [writes], so a client never sees `turn_ended` while the server still counts the turn as running, and a
     * turn started meanwhile cannot announce itself before this one has ended.
     */
    private suspend fun endTurn(ended: RpcEvent.TurnEnded): String? = writes.withLock {
        val next = synchronized(lock) {
            (inbox.drainSteers().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: inbox.takeFollowUp())
                .also { if (it == null) turn = null }
        }
        out(encodeRpcEvent(ended))
        next
    }

    /** Maps turn events to the wire, keeping what the assistant has said so far for an aborted turn's reply. */
    private fun relay(partial: StringBuilder): suspend (TurnEvent) -> Unit = { e ->
        if (e is TurnEvent.FollowUpStarted) partial.clear()
        if (e is TurnEvent.Token) partial.append(e.text)
        e.toRpcEvent(runtime::maskSecrets)?.let { emit(it) }
    }

    private suspend fun turnOnce(input: String) = announced(input, goal = false) { relay ->
        session = runtime.streamTurn(session, input, inbox, onEvent = relay)
        val last = session.branch().lastOrNull { it.role == EntryRole.ASSISTANT }
        RpcEvent.TurnEnded(last?.content.orEmpty(), last?.metadata?.get("stopReason") ?: "Completed")
    }

    private suspend fun goal(task: String, check: String?) = announced(task, goal = true) { relay ->
        val r = runtime.runGoal(session, task, check, planLog, onEvent = relay)
        r.changePlan?.let { emit(RpcEvent.PlanReady(it.id, it.steps.size)) }
        val stop = if (r.status == GoalStatus.Failed) "Error" else "Completed"
        RpcEvent.TurnEnded(r.reply.ifEmpty { r.error?.message.orEmpty() }, stop, r.status.name)
    }

    /** The replay report is the reply; on an abort it goes out as a token first, so the stopped turn's reply carries it. */
    private suspend fun replay(planId: String, planSession: String) = try {
        announced("approve_plan $planId", goal = false) { relay ->
            val report = { p: ChangePlan -> runtime.maskSecrets(p.replayReport()) }
            try {
                // The live session gets the outcome note when the plan is its own, so a later save can't drop it.
                val done = runtime.runPlan(planId, session.takeIf { it.id == planSession })
                RpcEvent.TurnEnded(report(done), if (done.status == PlanStatus.FAILED) "Error" else "Completed")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runtime.plan(planId)?.let { relay(TurnEvent.Token(report(it))) } }
                throw e
            }
        }
    } catch (e: CancellationException) {
        // Aborted before the replay started (e.g. while announcing it): nothing ran, so cancel rather than leave it approved.
        if (runtime.plan(planId)?.status == PlanStatus.APPROVED) runtime.cancelPlan(planId)
        throw e
    }

    /** Emits `turn_started`, runs [body], and turns a cancellation or failure into the matching `turn_ended`. */
    private suspend fun announced(
        input: String, goal: Boolean, body: suspend (suspend (TurnEvent) -> Unit) -> RpcEvent.TurnEnded,
    ): RpcEvent.TurnEnded {
        val partial = StringBuilder()
        return try {
            emit(RpcEvent.TurnStarted(input)) // inside the try: a cancelled job still ends the turn it announced
            started.join() // a fresh session's start hook first; abort still cancels the wait
            body(relay(partial))
        } catch (e: CancellationException) {
            val ended = RpcEvent.TurnEnded(partial.toString(), "UserStopped", GoalStatus.Interrupted.name.takeIf { goal })
            withContext(NonCancellable) { emit(ended) }
            throw e
        } catch (e: Exception) {
            RpcEvent.TurnEnded(e.message ?: e.toString(), "Error", GoalStatus.Failed.name.takeIf { goal })
        }
    }
}
