package dev.sophi.memory

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Scripted in-memory technique: lets plugin behavior be tested without the palace. */
private class ScriptedTechnique : MemoryTechnique {
    val observed = java.util.concurrent.CopyOnWriteArrayList<TurnObservation>()
    var recallAnswer: MemoryBlock? = null
    var throwOnObserve = false
    var observeDelayMs = 0L
    override suspend fun recall(query: RecallQuery): MemoryBlock? = recallAnswer
    override suspend fun observe(turn: TurnObservation) {
        kotlinx.coroutines.delay(observeDelayMs)
        if (throwOnObserve) error("encode kaput"); observed += turn
    }
    override suspend fun consolidate(nowMs: Long) = ConsolidationReport(0, 0, 0, 0, 0)
    override suspend fun forget(request: ForgetRequest) = ForgetResult(emptyList(), 0, emptyList())
    override suspend fun restore(id: String) = false
    override suspend fun search(query: String, k: Int) = emptyList<MemoryView>()
    override fun browse(filter: BrowseFilter) = emptyList<MemoryView>()
    override fun profileView() = emptyList<ProfileAttributeView>()
    override fun updateProfile(action: ProfileAction) = false
    override fun explainLastRecall(): String? = null
    override fun views(ids: List<String>) = ids.map { MemoryView(it, "text of $it", mapOf("source" to "src-$it")) }
}

class MemoryPluginTest : FunSpec({
    test("a recall is reported with the recalled memories, before its text goes into the turn") {
        val t = ScriptedTechnique().apply { recallAnswer = MemoryBlock("<memory_context>X</memory_context>", listOf("m1", "m2")) }
        val seen = mutableListOf<Pair<String, List<String>>>()
        val plugin = MemoryPlugin(t, onRecall = { sid, ms -> seen += sid to ms.map { it.metadata["source"].orEmpty() } })
        plugin.contribute("s1", "hi") shouldContain "<memory_context>"
        seen shouldBe listOf("s1" to listOf("src-m1", "src-m2"))
    }

    test("a recall that can't be reported puts nothing into the turn") {
        val t = ScriptedTechnique().apply { recallAnswer = MemoryBlock("<memory_context>X</memory_context>", listOf("m1")) }
        MemoryPlugin(t, onRecall = { _, _ -> error("can't check") }).contribute("s1", "hi") shouldBe null
    }

    test("a recall with no memories (profile only) isn't reported") {
        val t = ScriptedTechnique().apply { recallAnswer = MemoryBlock("<memory_context>profile</memory_context>", emptyList()) }
        var calls = 0
        MemoryPlugin(t, onRecall = { _, _ -> calls++ }).contribute("s1", "hi") shouldContain "profile"
        calls shouldBe 0
    }

    test("contribute renders the technique's recall via collectContext") {
        val t = ScriptedTechnique().apply { recallAnswer = MemoryBlock("<memory_context>X</memory_context>", listOf("mem_1")) }
        val plugin = MemoryPlugin(t)
        val registry = PluginRegistry().register(plugin)
        registry.collectContext("s1", "hi").single() shouldContain "<memory_context>"
    }

    test("AFTER_TURN with both sides of the turn triggers an async observe") {
        val t = ScriptedTechnique()
        val plugin = MemoryPlugin(t, clock = { 42L })
        val hook = plugin.hooks().single { it.point == HookPoint.AFTER_TURN }
        hook.invoke(HookContext("s1", userInput = "u", assistantReply = "a"))
        plugin.drainEncodes()
        t.observed.single().let {
            it.sessionId shouldBe "s1"; it.userInput shouldBe "u"
            it.assistantReply shouldBe "a"; it.nowMs shouldBe 42L
        }
    }

    test("AFTER_TURN forwards ambient=true onto the TurnObservation") {
        val t = ScriptedTechnique()
        val plugin = MemoryPlugin(t, clock = { 42L })
        val hook = plugin.hooks().single { it.point == HookPoint.AFTER_TURN }
        hook.invoke(HookContext("s1", userInput = "u", assistantReply = "a", ambient = true))
        plugin.drainEncodes()
        t.observed.single().ambient shouldBe true
    }

    test("AFTER_TURN without a reply does not observe; a throwing observe is swallowed") {
        val t = ScriptedTechnique()
        val plugin = MemoryPlugin(t)
        val hook = plugin.hooks().single { it.point == HookPoint.AFTER_TURN }
        hook.invoke(HookContext("s1", userInput = "u"))            // no reply
        plugin.drainEncodes()
        t.observed.size shouldBe 0
        t.throwOnObserve = true
        hook.invoke(HookContext("s1", userInput = "u", assistantReply = "a"))
        plugin.drainEncodes()                                      // must not throw
        plugin.close()
    }

    test("close() lets an in-flight encode finish: the session's last turn is not lost") {
        val t = ScriptedTechnique().apply { observeDelayMs = 300 }
        val plugin = MemoryPlugin(t)
        plugin.hooks().single { it.point == HookPoint.AFTER_TURN }
            .invoke(HookContext("s1", userInput = "remember the passport", assistantReply = "noted"))
        plugin.close()
        t.observed.single().userInput shouldBe "remember the passport"
    }

    test("close() gives up on an encode that outlasts the drain timeout") {
        val t = ScriptedTechnique().apply { observeDelayMs = 60_000 }
        val plugin = MemoryPlugin(t, drainTimeoutMs = 100)
        plugin.hooks().single { it.point == HookPoint.AFTER_TURN }
            .invoke(HookContext("s1", userInput = "u", assistantReply = "a"))
        val ms = kotlin.system.measureTimeMillis { plugin.close() }
        (ms < 5_000) shouldBe true
        t.observed.size shouldBe 0
    }

    test("palace() returns the technique when it is a JanesPalace") {
        val palace = JanesPalace(
            JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "test-model"),
            llmProvider = null, embeddingProvider = null
        )
        val plugin = MemoryPlugin(palace)

        plugin.palace() shouldBe palace
        plugin.close()
    }

    test("palace() returns null when the technique is not a JanesPalace") {
        val plugin = MemoryPlugin(ScriptedTechnique())

        plugin.palace() shouldBe null
    }

    test("recordSessionEnd is a silent no-op when the technique is not a JanesPalace") {
        val plugin = MemoryPlugin(ScriptedTechnique())

        plugin.recordSessionEnd("s1", success = true) // must not throw
    }

    test("recordSessionEnd on an empty JanesPalace (no recalls for the session) does not throw") {
        val palace = JanesPalace(
            JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "test-model"),
            llmProvider = null, embeddingProvider = null
        )
        val plugin = MemoryPlugin(palace)

        plugin.recordSessionEnd("s_never_recalled", success = true)

        plugin.close()
    }
})
