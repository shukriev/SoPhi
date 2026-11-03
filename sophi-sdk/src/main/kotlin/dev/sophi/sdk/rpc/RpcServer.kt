package dev.sophi.sdk.rpc

import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.TurnInbox
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.audit.noteDecision
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

    /** Emits `ready`, serves [input] until it closes, lets the running turn and its follow-ups finish, emits `exited`. */
    suspend fun run(input: ReceiveChannel<String>) = coroutineScope {
        emit(RpcEvent.Ready(session.id, runtime.config.model, runtime.provider?.name ?: "", memory))
        for (line in input) {
            if (line.isBlank()) continue
            decodeRpcCommand(line).fold({ handle(it) }, { emit(RpcEvent.Error("bad command: ${it.message}")) })
        }
        confirmations.closeInput()
        synchronized(lock) { turn }?.join()
        emit(RpcEvent.Exited)
    }

    private suspend fun CoroutineScope.handle(cmd: RpcCommand) {
        when (cmd) {
            is RpcCommand.Prompt -> queueOrStart(cmd.text, steer = false)
            is RpcCommand.FollowUp -> queueOrStart(cmd.text, steer = false)
            is RpcCommand.Steer -> queueOrStart(cmd.text, steer = true)
            is RpcCommand.Abort -> synchronized(lock) { turn }?.cancel() ?: emit(RpcEvent.Error("no turn is running", cmd.id))
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
        if (!startIfIdle { turnOnce(text) }) if (steer) inbox.steer(text) else inbox.followUp(text)
    }

    /**
     * Starts [first] and then whatever was queued behind it, unless a turn is running (false). Taking the
     * next message and going idle happen under [lock], so a message queued just as the turn ends still runs.
     * An abort drops what was queued.
     */
    private fun CoroutineScope.startIfIdle(first: suspend () -> Unit): Boolean = synchronized(lock) {
        if (turn != null) return false
        // Lazy, so [turn] is set before the job can look at it.
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                first()
                while (true) {
                    val next = synchronized(lock) {
                        (inbox.drainSteers().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: inbox.takeFollowUp())
                            .also { if (it == null) turn = null }
                    } ?: break
                    turnOnce(next)
                }
            } catch (e: CancellationException) {
                synchronized(lock) { inbox.drainAll(); turn = null }
                throw e
            }
        }
        turn = job
        job.start()
        true
    }

    /** Maps turn events to the wire, keeping what the assistant has said so far for an aborted turn's reply. */
    private fun relay(partial: StringBuilder): suspend (TurnEvent) -> Unit = { e ->
        if (e is TurnEvent.FollowUpStarted) partial.clear()
        if (e is TurnEvent.Token) partial.append(e.text)
        e.toRpcEvent(runtime::maskSecrets)?.let { emit(it) }
    }

    private suspend fun turnOnce(input: String) {
        emit(RpcEvent.TurnStarted(input))
        val partial = StringBuilder()
        try {
            session = runtime.streamTurn(session, input, inbox, onEvent = relay(partial))
            val last = session.branch().lastOrNull { it.role == EntryRole.ASSISTANT }
            emit(RpcEvent.TurnEnded(last?.content.orEmpty(), last?.metadata?.get("stopReason") ?: "Completed"))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { emit(RpcEvent.TurnEnded(partial.toString(), "UserStopped")) }
            throw e
        } catch (e: Exception) {
            emit(RpcEvent.TurnEnded(e.message ?: e.toString(), "Error"))
        }
    }

    private suspend fun goal(task: String, check: String?) {
        emit(RpcEvent.TurnStarted(task))
        val partial = StringBuilder()
        try {
            val r = runtime.runGoal(session, task, check, planLog, onEvent = relay(partial))
            r.changePlan?.let { emit(RpcEvent.PlanReady(it.id, it.steps.size)) }
            val stop = if (r.status == GoalStatus.Failed) "Error" else "Completed"
            emit(RpcEvent.TurnEnded(r.reply.ifEmpty { r.error?.message.orEmpty() }, stop, r.status.name))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { emit(RpcEvent.TurnEnded(partial.toString(), "UserStopped", GoalStatus.Interrupted.name)) }
            throw e
        } catch (e: Exception) {
            emit(RpcEvent.TurnEnded(e.message ?: e.toString(), "Error", GoalStatus.Failed.name))
        }
    }
}
