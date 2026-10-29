package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.BashSandbox
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.SandboxMode
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val ROWS = listOf(
    CLI_SETTINGS.single { it.key == "sandbox" },
    CLI_SETTINGS.single { it.key == "network" },
    CLI_SETTINGS.single { it.key == "auto" },
    CLI_SETTINGS.single { it.key == "max-tokens" },
)

class SettingsMenuTest : FunSpec({
    class Fixture(root: Path = tempdir().toPath(), godMode: Boolean = false) {
        val store = CliSettingsStore(tempdir().toPath().resolve("settings.json")) {}
        val sandbox = BashSandbox(root)
        val toggle = if (godMode) null else ToggleableConfirmationPolicy(ConfirmationPolicy.ALLOW_ALL, ConfirmationPolicy.DENY_ALL, autoModeEnabled = false)
        val controller = SettingsController(
            store, argv = emptyList(), thisRun = mapOf("max-tokens" to "4096"), help = mapOf("network" to "Let sandboxed bash use the network"),
            bashSandbox = sandbox, autoToggle = toggle, settings = ROWS,
        )
        val menu = SettingsMenu(controller)
        suspend fun keys(vararg k: TurnKey) = k.forEach { menu.onKey(it) }
    }

    test("Down moves the cursor, render marks it and shows its help") {
        val f = Fixture()
        f.menu.render().lines().first { it.startsWith(">") } shouldContain "sandbox"
        f.keys(TurnKey.Down)
        val text = f.menu.render()
        text.lines().first { it.startsWith(">") } shouldContain "network"
        text shouldContain "Let sandboxed bash use the network"
    }

    test("Enter on a Bool flips it, applies it live and saves it") {
        val f = Fixture()
        runBlocking { f.keys(TurnKey.Down, TurnKey.Enter) }
        f.sandbox.network shouldBe false
        f.store.saved() shouldBe mapOf("network" to "false")
        f.menu.render() shouldContain "network"
    }

    test("Enter on a Choice cycles to the next value") {
        val f = Fixture()
        runBlocking { f.keys(TurnKey.Enter) }
        if (dev.sophi.core.tools.SeatbeltSandbox.available) {
            f.sandbox.mode shouldBe SandboxMode.SEATBELT
            f.store.saved()["sandbox"] shouldBe "seatbelt"
        }
    }

    test("Int edit: Enter, Backspace x4, type 42, Enter saves 42 and marks next start") {
        val f = Fixture()
        runBlocking {
            f.keys(TurnKey.Down, TurnKey.Down, TurnKey.Down, TurnKey.Enter)
            f.menu.render() shouldContain "4096_"
            f.keys(TurnKey.Backspace, TurnKey.Backspace, TurnKey.Backspace, TurnKey.Backspace, TurnKey.Text('4'), TurnKey.Text('2'), TurnKey.Enter)
        }
        f.store.saved() shouldBe mapOf("max-tokens" to "42")
        f.menu.render().lines().first { it.startsWith(">") } shouldContain "(next start)"
    }

    test("a bad Int shows the error and saves nothing") {
        val f = Fixture()
        runBlocking { f.keys(TurnKey.Down, TurnKey.Down, TurnKey.Down, TurnKey.Enter, TurnKey.Text('x'), TurnKey.Enter) }
        f.menu.render() shouldContain "✗ max-tokens is a whole number"
        f.store.saved() shouldBe emptyMap()
    }

    test("Esc during an edit cancels it and keeps the menu open; a second Esc closes") {
        val f = Fixture()
        runBlocking { f.keys(TurnKey.Down, TurnKey.Down, TurnKey.Down, TurnKey.Enter, TurnKey.Text('9')) }
        f.menu.onEsc() shouldBe false
        f.menu.render() shouldNotContain "_"
        f.menu.onEsc() shouldBe true
        f.store.saved() shouldBe emptyMap()
    }

    test("a refused switch shows the reason and keeps the old value") {
        val f = Fixture(root = tempdir().toPath().resolve("a:b").createDirectories())
        runBlocking { f.controller.commit(ROWS[0], "docker") } shouldBe "a workspace path with ':' can't be mounted in Docker"
        f.sandbox.mode shouldBe SandboxMode.OFF
        f.store.saved() shouldBe emptyMap()
    }

    test("d resets to the default") {
        val f = Fixture()
        runBlocking { f.keys(TurnKey.Down, TurnKey.Enter) }
        f.sandbox.network shouldBe false
        runBlocking { f.keys(TurnKey.Text('d')) }
        f.sandbox.network shouldBe true
        f.store.saved() shouldBe emptyMap()
    }

    test("an empty text value resets the setting") {
        val f = Fixture()
        val system = CLI_SETTINGS.single { it.key == "system" }
        runBlocking { f.controller.commit(system, "be brief") } shouldBe null
        runBlocking { f.controller.commit(system, "") } shouldBe null
        f.store.saved() shouldBe emptyMap()
    }

    test("starting a container says keys wait until it's done").config(enabled = dev.sophi.core.tools.SeatbeltSandbox.available) {
        val f = Fixture(root = tempdir().toPath().resolve("a:b").createDirectories())
        f.sandbox.set(SandboxMode.SEATBELT) shouldBe null
        val shown = mutableListOf<String>()
        val menu = SettingsMenu(f.controller) { shown += it }
        runBlocking { menu.onKey(TurnKey.Enter) }
        shown.single() shouldContain "keys wait until it's done"
    }

    test("auto under god mode reports it is fixed") {
        val f = Fixture(godMode = true)
        runBlocking { f.controller.commit(ROWS[2], "true") } shouldBe "auto mode is fixed under --god-mode"
    }

    test("/settings opens the menu: Down Enter Esc saves once and closes") {
        val f = Fixture()
        val input = ScriptedInputSource(emptyList())
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"),
            settings = SettingsController(f.store, emptyList(), emptyMap(), emptyMap(), f.sandbox, f.toggle, ROWS, interactive = true),
            input = input) { out += it }
        runBlocking {
            val job = async { handler.handle("/settings", AgentSession(id = "s")) }
            input.type(TurnKey.Down, TurnKey.Enter)
            repeat(20) { yield() }
            input.signalEsc()
            job.await()
        }
        f.store.saved() shouldBe mapOf("network" to "false")
    }

    test("/settings max-tokens 2048 saves it and says it applies next start") {
        val f = Fixture()
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), settings = f.controller) { out += it }
        runBlocking { handler.handle("/settings max-tokens 2048", AgentSession(id = "s")) }
        f.store.saved() shouldBe mapOf("max-tokens" to "2048")
        out.joinToString("\n") shouldContain "next start"
    }

    test("/settings sandbox docker with a ':' workspace reports the reason and saves nothing") {
        val f = Fixture(root = tempdir().toPath().resolve("a:b").createDirectories())
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), settings = f.controller) { out += it }
        runBlocking { handler.handle("/settings sandbox docker", AgentSession(id = "s")) }
        out.joinToString("\n") shouldContain "can't be mounted in Docker"
        f.store.file.exists() shouldBe false
    }

    test("/settings without a terminal lists every setting") {
        val f = Fixture()
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), settings = f.controller) { out += it }
        runBlocking { handler.handle("/settings", AgentSession(id = "s")) }
        out.joinToString("\n") shouldContain "max-tokens"
    }

    class ModelFixture(switchResult: String? = null) {
        val store = CliSettingsStore(tempdir().toPath().resolve("settings.json")) {}
        var model = "a"
        val switched = mutableListOf<String>()
        val meter = dev.sophi.sdk.CostMeter({ m ->
            dev.sophi.sdk.ModelInfo("anthropic", m, null, null, 1_000_000.0, 0.0, null, false, false, false, null)
        }, maxUsd = 0.5)
        val modelRow = CLI_SETTINGS.single { it.key == "model" }
        val budgetRow = CLI_SETTINGS.single { it.key == "max-cost-usd" }
        val controller = SettingsController(
            store, argv = emptyList(), thisRun = emptyMap(), help = emptyMap(),
            bashSandbox = null, autoToggle = null, settings = listOf(modelRow, budgetRow),
            costMeter = meter, currentModel = { model },
            switchModel = { id -> switchResult ?: null.also { switched += id; model = id } },
        )
    }

    test("a model switch goes through switchModel and is saved") {
        val f = ModelFixture()
        runBlocking { f.controller.commit(f.modelRow, "b") } shouldBe null
        f.switched shouldBe listOf("b")
        f.store.saved()["model"] shouldBe "b"
        f.controller.current(f.modelRow) shouldBe "b"
    }

    test("a failed switch saves nothing") {
        val f = ModelFixture(switchResult = "b isn't on the server")
        runBlocking { f.controller.commit(f.modelRow, "b") } shouldBe "b isn't on the server"
        f.store.saved().containsKey("model") shouldBe false
    }

    test("resetting model removes it from the file without switching") {
        val f = ModelFixture()
        runBlocking { f.controller.commit(f.modelRow, "b") }
        runBlocking { f.controller.reset(f.modelRow) } shouldBe null
        f.store.saved().containsKey("model") shouldBe false
        f.switched shouldBe listOf("b")
        f.model shouldBe "b"
    }

    test("raising max-cost-usd lets the next turn run") {
        val f = ModelFixture()
        f.meter.record("a", dev.sophi.ai.api.TokenUsage(1, 0))
        (f.meter.overBudget() != null) shouldBe true
        f.controller.current(f.budgetRow) shouldBe "0.5"
        runBlocking { f.controller.commit(f.budgetRow, "2") } shouldBe null
        f.meter.overBudget() shouldBe null
        f.store.saved()["max-cost-usd"] shouldBe "2"
        runBlocking { f.controller.commit(f.budgetRow, "0") } shouldBe null
        f.meter.maxUsd shouldBe null
    }

    test("/model shows the live model and window, and /model <id> switches") {
        val f = ModelFixture()
        var window = 32_000
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "a"),
            settings = f.controller, contextWindowTokens = window,
            live = { AgentConfig(model = f.model) to window }) { out += it }
        runBlocking {
            handler.handle("/model", AgentSession(id = "s"))
            window = 16_000
            handler.handle("/model b", AgentSession(id = "s"))
            handler.handle("/model", AgentSession(id = "s"))
        }
        out.first() shouldContain "model: a (32000 tokens)"
        f.switched shouldBe listOf("b")
        out.last() shouldContain "model: b (16000 tokens)"
    }
})
