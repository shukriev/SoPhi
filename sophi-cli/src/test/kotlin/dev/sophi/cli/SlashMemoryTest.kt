package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.memory.MemoryPlugin
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.memory.jane.Memory
import dev.sophi.memory.jane.PalaceStore
import dev.sophi.memory.jane.Provenance
import dev.sophi.memory.jane.Room
import dev.sophi.memory.jane.SalienceSignals
import dev.sophi.memory.jane.Sensitivity
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class SlashMemoryTest : FunSpec({
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val output = mutableListOf<String>()

    beforeTest { output.clear() }

    fun palacePlugin(): MemoryPlugin {
        val palace = JanesPalace(JanesPalaceConfig(home = tempdir().toPath()), null, null)
        return MemoryPlugin(palace)
    }

    test("/memory shows disabled message when memory is not enabled") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/memory list", AgentSession(id = "s1"))
        output shouldBe listOf("Memory is not enabled.")
    }

    test("/memory (no args) lists memories, showing '(no memories)' when empty") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory", AgentSession(id = "s1"))
        output shouldBe listOf("(no memories)")
    }

    test("/memory show with no id shows usage") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory show", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /memory show <id>")
    }

    test("/memory show reports not found for an unknown id") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory show nope", AgentSession(id = "s1"))
        output shouldBe listOf("Not found: nope")
    }

    test("/memory threads shows '(no threads)' when empty") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory threads", AgentSession(id = "s1"))
        output shouldBe listOf("(no threads)")
    }

    test("/memory profile shows '(empty profile)' when empty") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory profile", AgentSession(id = "s1"))
        output shouldBe listOf("(empty profile)")
    }

    test("/memory profile confirm on an unknown attribute reports 'No such attribute.'") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory profile confirm some.path", AgentSession(id = "s1"))
        output shouldBe listOf("No such attribute.")
    }

    test("/memory profile confirm with no path shows usage") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory profile confirm", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /memory profile confirm <path>")
    }

    test("/memory profile correct with missing args shows usage") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory profile correct some.path", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /memory profile correct <path> <value>")
    }

    test("/memory why shows '(no recall recorded yet)' when nothing was recalled") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory why", AgentSession(id = "s1"))
        output shouldBe listOf("(no recall recorded yet)")
    }

    test("/memory with an unknown subcommand reports it") {
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = palacePlugin()) { output.add(it) }
        handler.handle("/memory banana", AgentSession(id = "s1"))
        output shouldBe listOf("Unknown /memory subcommand: banana  Available: list show threads profile why")
    }

    test("/memory list accepts --source and --provenance filters alongside an optional room") {
        // Seed through PalaceStore and close it before JanesPalace opens the same home --
        // ArcadeDB allows one open process per directory.
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        val base = Memory(
            "x", "text", Room.EPISODES, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
            Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s"
        )
        seed.upsertMemory(base.copy(id = "mem_ambient", text = "overheard thing", sourceSessionId = "ambient"))
        seed.upsertMemory(base.copy(id = "mem_chat", text = "typed thing", sourceSessionId = "sess_1"))
        seed.close()
        val plugin = MemoryPlugin(JanesPalace(JanesPalaceConfig(home = home), null, null))
        val handler = SlashHandler(sessionManager, null, config, memoryPlugin = plugin) { output.add(it) }

        handler.handle("/memory list --source=ambient", AgentSession(id = "s1"))
        output.single().contains("overheard thing") shouldBe true

        output.clear()
        handler.handle("/memory list episodes --source=ambient", AgentSession(id = "s1"))
        output.single().contains("overheard thing") shouldBe true

        output.clear()
        handler.handle("/memory list --source=sess_1", AgentSession(id = "s1"))
        output.single().contains("typed thing") shouldBe true

        output.clear()
        handler.handle("/memory list --provenance=third_party", AgentSession(id = "s1"))
        output shouldBe listOf("(no memories)")

        // The pre-existing bare-room form must keep working unchanged.
        output.clear()
        handler.handle("/memory list episodes", AgentSession(id = "s1"))
        output shouldHaveSize 2
    }
})
