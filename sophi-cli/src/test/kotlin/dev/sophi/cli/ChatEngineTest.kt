package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class ChatEngineTest : FunSpec({
    val loop = mockk<AgentLoop>()
    val config = AgentConfig(model = "test-model")
    lateinit var engine: ChatEngine

    beforeTest {
        clearMocks(loop)
        engine = ChatEngine(loop, config)
    }

    fun fakeRound(s: AgentSession, input: String, reply: String): AgentSession {
        s.append(EntryRole.USER, input)
        s.append(EntryRole.ASSISTANT, reply)
        return s
    }

    test("run() calls loop.turn() once per non-exit line") {
        val session = AgentSession(id = "s1")
        coEvery { loop.turn(any(), any(), any(), any()) } answers {
            fakeRound(firstArg(), secondArg(), "ok")
        }
        engine.run(session, sequenceOf("Hello", "World", "exit"), {})
        coVerify(exactly = 2) { loop.turn(any(), any(), any(), any()) }
    }

    test("run() feeds assistant content to output for each line") {
        val session = AgentSession(id = "s1")
        var n = 0
        coEvery { loop.turn(any(), any(), any(), any()) } answers {
            n++
            fakeRound(firstArg(), secondArg(), "answer $n")
        }
        val out = mutableListOf<String>()
        engine.run(session, sequenceOf("q1", "q2"), out::add)
        out shouldBe listOf("answer 1", "answer 2")
    }

    test("run() stops on 'exit' before calling loop") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("exit", "unreachable"), {})
        coVerify(exactly = 0) { loop.turn(any(), any(), any(), any()) }
    }

    test("run() stops on 'quit' before calling loop") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("quit", "unreachable"), {})
        coVerify(exactly = 0) { loop.turn(any(), any(), any(), any()) }
    }

    test("run() skips empty and blank-only lines") {
        val session = AgentSession(id = "s1")
        coEvery { loop.turn(any(), any(), any(), any()) } answers {
            fakeRound(firstArg(), secondArg(), "ok")
        }
        val out = mutableListOf<String>()
        engine.run(session, sequenceOf("", "  ", "hi", ""), out::add)
        coVerify(exactly = 1) { loop.turn(any(), any(), any(), any()) }
        out shouldHaveSize 1
    }

    test("run() handles empty input sequence without error") {
        val session = AgentSession(id = "s1")
        engine.run(session, emptySequence(), {})
        coVerify(exactly = 0) { loop.turn(any(), any(), any(), any()) }
    }
})
