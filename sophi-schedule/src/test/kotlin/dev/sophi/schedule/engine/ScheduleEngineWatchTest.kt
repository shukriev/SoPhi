package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.EffectKind
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

private const val URL = "https://shop.example/kb"

class ScheduleEngineWatchTest : FunSpec({
    /** Stands in for fetch_url: same name, effects and subjects; replies with the next page. */
    class FakeFetch(var page: String = "HTTP 200 $URL\n\nKeyboard 99 EUR") : Tool {
        val fetched = mutableListOf<String>()
        override val name = "fetch_url"
        override val description = "fetch"
        override val parametersJson = "{}"
        override val outsideText = true
        override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
        override fun effects(argumentsJson: String) = setOf(EffectKind.NETWORK)
        override fun permissionSubjects(argumentsJson: String) = listOf(PermissionSubject(URL))
        override suspend fun execute(argumentsJson: String): String { fetched += argumentsJson; return page }
    }

    data class Rig(val engine: ScheduleEngine, val store: TaskStore, val runLog: RunLog, val fetch: FakeFetch,
                   val notified: MutableList<RunRecord>, val provider: LLMProvider, val home: java.nio.file.Path)

    fun rig(rules: PermissionRules? = null): Rig {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val runLog = RunLog(home.resolve("runs.jsonl"))
        val fetch = FakeFetch()
        val notified = mutableListOf<RunRecord>()
        val provider = mockk<LLMProvider>()
        val engine = ScheduleEngine(store, runLog, provider, ToolRegistry().register(fetch),
            FileSessionManager(createTempDirectory("watch-test")), Notifier { _, r -> notified += r },
            model = "m", contextWindowTokens = 100_000, permissions = rules)
        return Rig(engine, store, runLog, fetch, notified, provider, home)
    }

    fun watch(r: Rig) = r.store.add(ScheduledTask(name = "keyboard price", trigger = Trigger.Interval(3600),
        mode = TaskMode.Watch(URL), prompt = "Watch $URL", toolGrants = setOf("fetch_url")))

    test("the first check saves a snapshot quietly, an unchanged page stays quiet, a change notifies with what changed") {
        val r = rig()
        val id = watch(r).id

        val first = runBlocking { r.engine.runNow(id) }!!
        first.outcome shouldBe RunOutcome.Succeeded
        first.summary shouldStartWith WATCH_UNCHANGED
        r.notified.size shouldBe 0

        r.fetch.page = "HTTP 200 $URL\n\n  Keyboard   99 EUR  \n"
        runBlocking { r.engine.runNow(id) }!!.summary shouldStartWith WATCH_UNCHANGED
        r.notified.size shouldBe 0

        r.fetch.page = "HTTP 200 $URL\n\nKeyboard 79 EUR"
        val changed = runBlocking { r.engine.runNow(id) }!!
        changed.outcome shouldBe RunOutcome.Succeeded
        changed.summary shouldStartWith "$PAGE_CHANGED: $URL"
        changed.summary shouldContain "+ Keyboard 79 EUR"
        changed.summary shouldContain "- Keyboard 99 EUR"
        r.notified.single() shouldBe changed
        r.runLog.forTask(id).size shouldBe 3

        // The snapshot moved on: the same page again is no change.
        runBlocking { r.engine.runNow(id) }!!.summary shouldStartWith WATCH_UNCHANGED
        r.notified.size shouldBe 1
        r.fetch.fetched.first() shouldBe """{"url":"$URL"}"""
        Files.readString(r.home.resolve("watch").resolve("$id.txt")) shouldBe "Keyboard 79 EUR"
        verify(exactly = 0) { r.provider.stream(any()) }
    }

    test("a 'deny network when unattended' rule skips the check with the rule as the reason, without fetching") {
        val rules = PermissionRules(listOf(PermissionRule(effect = Effect.DENY, kind = EffectKind.NETWORK, unattended = true)),
            emptyList(), null, null, emptySet())
        val r = rig(rules)
        val record = runBlocking { r.engine.runNow(watch(r).id) }!!

        record.outcome shouldBe RunOutcome.Skipped("fetch_url $URL blocked by rule: network deny (unattended)")
        r.fetch.fetched.size shouldBe 0
    }

    test("without a fetch_url grant or allow rule, nobody is here to approve the fetch") {
        val r = rig()
        val task = r.store.add(ScheduledTask(name = "w", trigger = Trigger.Manual, mode = TaskMode.Watch(URL), prompt = "w"))
        val record = runBlocking { r.engine.runNow(task.id) }!!

        (record.outcome as RunOutcome.Skipped).reason shouldContain "nobody is here to approve"
        r.fetch.fetched.size shouldBe 0
    }

    test("an error page or a fetch error fails the check and keeps the snapshot") {
        val r = rig()
        val id = watch(r).id
        runBlocking { r.engine.runNow(id) }
        r.fetch.page = "HTTP 503 $URL\n\nService unavailable"
        runBlocking { r.engine.runNow(id) }!!.outcome shouldBe RunOutcome.Failed("HTTP 503 $URL")
        r.fetch.page = "Error: could not resolve host: shop.example"
        runBlocking { r.engine.runNow(id) }!!.outcome shouldBe RunOutcome.Failed("could not resolve host: shop.example")
        Files.readString(r.home.resolve("watch").resolve("$id.txt")) shouldBe "Keyboard 99 EUR"
    }
})
