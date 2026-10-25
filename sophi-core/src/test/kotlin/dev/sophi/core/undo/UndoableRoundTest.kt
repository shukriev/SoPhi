package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

class UndoableRoundTest : FunSpec({
    fun store() = createTempDirectory("ur-root").let { it to CheckpointStore(it, createTempDirectory("ur-home")) {} }

    test("records the snapshot and the block's moves on one replay=false entry, which FileRestorer undoes") {
        val (root, store) = store()
        val session = AgentSession(id = "s")
        val result = runBlocking {
            undoableRound(session, store, "goal") {
                root.resolve("new.txt").writeText("n")
                currentCoroutineContext()[RoundChanges]!!.record(FileMove("/a", "/b"))
                42
            }
        }
        result shouldBe 42
        val e = session.entries.single()
        e.content shouldBe "[goal file changes]"
        e.metadata["replay"] shouldBe "false"
        e.metadata shouldContainKey "checkpoint"
        e.metadata shouldContainKey "checkpointMs"
        e.metadata["moves"] shouldBe """[{"from":"/a","to":"/b"}]"""
        FileRestorer(store).apply(session, FileRestorer(store).plan(session)!!)
        root.resolve("new.txt").exists() shouldBe false
    }

    test("appends nothing without a checkpoint or moves") {
        val session = AgentSession(id = "s")
        runBlocking { undoableRound(session, null, "goal") { } }
        session.entries.shouldBeEmpty()
    }

    test("appends the entry even when the block throws, then rethrows") {
        val session = AgentSession(id = "s")
        val e = shouldThrow<IllegalStateException> {
            runBlocking {
                undoableRound(session, null, "plan") {
                    currentCoroutineContext()[RoundChanges]!!.record(FileMove("/a", "/b"))
                    error("boom")
                }
            }
        }
        e.message shouldContain "boom"
        session.entries.single().metadata["moves"] shouldBe """[{"from":"/a","to":"/b"}]"""
    }

    test("a snapshot that throws never blocks the run") {
        val store = io.mockk.mockk<CheckpointStore> { io.mockk.every { snapshot() } throws IllegalStateException("disk gone") }
        val session = AgentSession(id = "s")
        runBlocking { undoableRound(session, store, "goal") { 7 } } shouldBe 7
        session.entries.shouldBeEmpty()
    }

    test("a round that changed no files appends nothing, so it doesn't use up an /undo") {
        val (root, store) = store()
        root.resolve("a.txt").writeText("a")
        val session = AgentSession(id = "s")
        runBlocking { undoableRound(session, store, "goal") { } }
        session.entries.shouldBeEmpty()
    }
})
