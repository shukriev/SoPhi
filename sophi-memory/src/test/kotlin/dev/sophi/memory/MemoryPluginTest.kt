package dev.sophi.memory

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Scripted in-memory technique: lets plugin behavior be tested without the palace. */
private class ScriptedTechnique : MemoryTechnique {
    val observed = java.util.concurrent.CopyOnWriteArrayList<TurnObservation>()
    var recallAnswer: MemoryBlock? = null
    var throwOnObserve = false
    override suspend fun recall(query: RecallQuery): MemoryBlock? = recallAnswer
    override suspend fun observe(turn: TurnObservation) {
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
}

class MemoryPluginTest : FunSpec({
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
})
