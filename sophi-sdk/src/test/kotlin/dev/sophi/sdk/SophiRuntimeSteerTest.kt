package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.TurnInbox
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class SophiRuntimeSteerTest : FunSpec({
    /** Records every hook; [onAfter] runs inside AFTER_TURN with its 1-based call number. */
    class Recorder(val onAfter: (Int) -> Unit = {}) : SophiPlugin {
        override val name = "recorder"
        val before = mutableListOf<String?>()
        val after = mutableListOf<String?>()
        override fun hooks() = listOf(
            object : AgentHook {
                override val point = HookPoint.BEFORE_TURN
                override suspend fun invoke(context: HookContext) { before += context.userInput }
            },
            object : AgentHook {
                override val point = HookPoint.AFTER_TURN
                override suspend fun invoke(context: HookContext) { after += context.userInput; onAfter(after.size) }
            }
        )
    }

    fun runtime(recorder: Recorder, onStream: (Int) -> Unit = {}, replies: List<String>): SophiRuntime {
        val provider = mockk<LLMProvider>()
        var n = 0
        every { provider.stream(any()) } answers {
            val i = n++
            flow { onStream(i); emit(StreamEvent.Content(replies[i])) }
        }
        return Sophi.runtime {
            this.provider = provider
            model = "test-model"
            contextWindowTokens(100_000)
            sessionsDir = tempdir().toPath()
            plugin(recorder)
        }
    }

    fun AgentSession.userTexts() = entries.filter { it.role == EntryRole.USER }.map { it.content }

    test("a follow-up starts a new turn after the final answer") {
        val inbox = TurnInbox()
        val rec = Recorder()
        val rt = runtime(rec, onStream = { if (it == 0) inbox.followUp("then B") }, replies = listOf("A", "B"))
        val events = mutableListOf<TurnEvent>()

        val session = rt.streamTurn(AgentSession(id = "s1", title = "t"), "first", inbox) { events += it }

        events shouldContain TurnEvent.FollowUpStarted("then B")
        session.userTexts() shouldBe listOf("first", "then B")
        session.entries.last { it.role == EntryRole.ASSISTANT }.content shouldBe "B"
        rec.before shouldBe listOf("first", "then B")
        rec.after shouldBe listOf("first", "then B")
    }

    test("a leftover steer runs as a follow-up") {
        val inbox = TurnInbox()
        val rec = Recorder { if (it == 1) inbox.steer("late steer") }
        val rt = runtime(rec, replies = listOf("A", "B"))

        val session = rt.streamTurn(AgentSession(id = "s1", title = "t"), "first", inbox) { }

        session.userTexts() shouldBe listOf("first", "late steer")
        rec.after shouldBe listOf("first", "late steer")
    }

    test("interrupt or error leaves queued follow-ups in the inbox") {
        val inbox = TurnInbox()
        val rec = Recorder()
        val rt = runtime(rec, onStream = { inbox.followUp("later"); error("boom") }, replies = listOf("A", "B"))

        shouldThrow<Exception> { rt.streamTurn(AgentSession(id = "s1", title = "t"), "first", inbox) { } }

        inbox.drainAll() shouldBe listOf("later")
        rec.before shouldBe listOf("first")
    }

    // Between taking a follow-up from the inbox and AgentLoop starting (BEFORE_TURN hooks, recall),
    // nothing saves it yet, so a cancel there must put it back or the text is lost.
    test("a follow-up cancelled before its loop starts goes back to the inbox") {
        val inbox = TurnInbox()
        val blocked = CompletableDeferred<Unit>()
        val stall = object : SophiPlugin {
            override val name = "stall"
            override fun hooks() = listOf(object : AgentHook {
                override val point = HookPoint.BEFORE_TURN
                override suspend fun invoke(context: HookContext) {
                    if (context.userInput == "then B") { blocked.complete(Unit); awaitCancellation() }
                }
            })
        }
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } answers { flow { inbox.followUp("then B"); emit(StreamEvent.Content("A")) } }
        val rt = Sophi.runtime {
            this.provider = provider
            model = "test-model"
            contextWindowTokens(100_000)
            sessionsDir = tempdir().toPath()
            plugin(stall)
        }

        var thrown: Throwable? = null
        val turn = launch {
            try { rt.streamTurn(AgentSession(id = "s1", title = "t"), "first", inbox) { } } catch (e: Throwable) { thrown = e; throw e }
        }
        blocked.await()
        turn.cancelAndJoin()

        thrown.shouldBeInstanceOf<CancellationException>()
        inbox.drainAll() shouldBe listOf("then B")
    }
})
