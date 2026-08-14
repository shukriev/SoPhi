package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.tools.AutoModeConfirmationPolicy
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.sdk.DefaultPrompt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.nio.file.Path
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

/** Shared by BuildCliRuntimeTest and TuiEngineTest's end-to-end tests. */
internal fun optionsFor(
    dir: Path,
    autoMode: Boolean = false,
    godMode: Boolean = false,
    mcpConfigPath: Path = dir.resolve("mcp.json")
) = CliOptions(
    model = "test-model",
    maxTokens = 4096,
    contextWindowTokens = TEST_CONTEXT_WINDOW,
    systemPrompt = null,
    sessionsDir = dir.resolve("sessions").toString(),
    agentsDir = dir.resolve("agents").toString(),
    scheduleDir = dir.resolve("schedule").toString(),
    plansDir = dir.resolve("plans").toString(),
    mcpConfigPath = mcpConfigPath.toString(),
    // Points learning at the test's own directory; LearningConfig otherwise defaults to the
    // developer's real ~/.sophi/learning.
    learningHome = dir.resolve("learning"),
    braveApiKey = null,
    autoMode = autoMode,
    godMode = godMode,
    // The hub is a live socket; these tests are about wiring, so stay local.
    noRemote = true
)

/**
 * Characterization tests for the CLI's wiring: they assert *what gets registered and chosen*,
 * not how. Written against the hand-wired implementation so the SDK migration can be checked
 * against them without editing the assertions.
 */
class BuildCliRuntimeTest : FunSpec({

    suspend fun build(
        autoMode: Boolean = false,
        godMode: Boolean = false,
        writeMcpConfig: Boolean = false
    ): CliRuntime {
        val dir = tempdir().toPath()
        val mcpPath = dir.resolve("mcp.json")
        if (writeMcpConfig) mcpPath.writeText("""{"servers":[]}""")
        return buildCliRuntime(
            opts = optionsFor(dir, autoMode = autoMode, godMode = godMode),
            provider = mockk<LLMProvider>(relaxed = true),
            terminal = Terminal(),
            input = ScriptedInputSource(emptyList())
        )
    }

    test("registers the builtin, calendar, schedule and goal-decomposition tools") {
        build().registry.names() shouldContainAll listOf(
            "read_file", "write_file", "grep", "glob", "edit_file", "bash", "fetch_url",
            "get_current_datetime", "manage_scheduled_task",
            "create_calendar_event", "list_calendar_events", "get_calendar_event",
            "update_calendar_event", "delete_calendar_event", "list_calendars",
            // Registered post-build() because it needs the registry it is registered into.
            "decompose_goal"
        )
    }

    test("a missing mcp.json neither throws nor registers MCP tools") {
        // MCP tools are namespaced "<server>__<tool>"; with no config file none should appear.
        build(writeMcpConfig = false).registry.names().none { it.contains("__") } shouldBe true
    }

    test("an empty mcp.json is loaded without error") {
        build(writeMcpConfig = true).registry.names() shouldContainAll listOf("bash")
    }

    test("the confirmation policy is toggleable by default") {
        val cli = build()
        cli.confirmationPolicy.shouldBeInstanceOf<ToggleableConfirmationPolicy>()
        cli.autoModeToggle.shouldNotBeNull()
    }

    test("god mode replaces the toggleable policy with a fixed auto-mode policy") {
        val cli = build(godMode = true)
        cli.autoModeToggle shouldBe null
        cli.confirmationPolicy.shouldBeInstanceOf<AutoModeConfirmationPolicy>()
    }

    test("the --system prompt flows through to the agent config") {
        val dir = tempdir().toPath()
        val cli = buildCliRuntime(
            opts = optionsFor(dir).copy(systemPrompt = "BASE PROMPT"),
            provider = mockk<LLMProvider>(relaxed = true),
            terminal = Terminal(),
            input = ScriptedInputSource(emptyList())
        )
        cli.runtime.config.systemPrompt.shouldNotBeNull() shouldContain "BASE PROMPT"
    }

    test("memory enabled without an embedding model warns and leaves memory off") {
        val dir = tempdir().toPath()
        val warnings = mutableListOf<String>()
        val cli = buildCliRuntime(
            opts = optionsFor(dir).copy(memoryEnabled = true, embeddingModel = null),
            provider = mockk<LLMProvider>(relaxed = true),
            terminal = Terminal(),
            input = ScriptedInputSource(emptyList()),
            onWarning = { warnings.add(it) }
        )
        cli.runtime.memoryPlugin shouldBe null
        warnings.single() shouldContain "memory: disabled — --memory needs --embedding-model"
    }

    test("memory enabled without an embedding base URL or --base-url warns and leaves memory off") {
        val dir = tempdir().toPath()
        val warnings = mutableListOf<String>()
        val cli = buildCliRuntime(
            opts = optionsFor(dir).copy(
                memoryEnabled = true, embeddingModel = "nomic-embed-text",
                embeddingBaseUrl = null, baseUrl = null
            ),
            provider = mockk<LLMProvider>(relaxed = true),
            terminal = Terminal(),
            input = ScriptedInputSource(emptyList()),
            onWarning = { warnings.add(it) }
        )
        cli.runtime.memoryPlugin shouldBe null
        warnings.single() shouldContain "memory: disabled — --memory needs --embedding-model"
    }

    test("model and maxTokens flow through to the agent config") {
        val cli = build()
        cli.runtime.config.model shouldBe "test-model"
        cli.runtime.config.maxTokens shouldBe 4096
    }

    // A fresh learning home has no distilled lessons yet, so promptSections contributes nothing.
    // With no --system passed, the prompt is exactly the SDK default (RuntimeBuilder always
    // includes DefaultPrompt.BASE now) — pinning this so the migration cannot quietly change it.
    test("with no --system and an empty learning home the system prompt is exactly the default") {
        build().runtime.config.systemPrompt shouldBe DefaultPrompt.BASE
    }

    test("a new session is created with an id but is not persisted until a turn saves it") {
        val cli = build()
        cli.session.id.isNotBlank() shouldBe true
        shouldThrow<IllegalArgumentException> { cli.runtime.sessionManager.load(cli.session.id) }
    }
})
