package dev.sophi.cli

import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class OrchestratorWiringTest : FunSpec({
    test("switch off creates no task") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        bootstrapOrchestrator(store) { null }
        store.list() shouldBe emptyList()
    }

    test("an unset, empty, or misspelled value all mean off, not on") {
        listOf(null, "", "TRUE ", "1", "yes").forEach { value ->
            val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
            bootstrapOrchestrator(store) { if (it == ORCHESTRATOR_ENABLED_ENV) value else null }
            store.list() shouldBe emptyList()
        }
    }

    test("switch on creates the orchestrator task, enabled, with no toolGrants") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        bootstrapOrchestrator(store) { if (it == ORCHESTRATOR_ENABLED_ENV) "true" else null }

        val tasks = store.list()
        tasks shouldHaveSize 1
        tasks.single().name shouldBe ORCHESTRATOR_TASK_NAME
        tasks.single().enabled shouldBe true
        tasks.single().toolGrants shouldBe emptySet()
        (tasks.single().mode is TaskMode.Goal) shouldBe true
    }

    test("switch on twice does not duplicate the task") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        val on: (String) -> String? = { if (it == ORCHESTRATOR_ENABLED_ENV) "true" else null }
        bootstrapOrchestrator(store, on)
        bootstrapOrchestrator(store, on)

        store.list() shouldHaveSize 1
    }

    test("switch off after being on disables the existing task instead of removing it") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        bootstrapOrchestrator(store) { if (it == ORCHESTRATOR_ENABLED_ENV) "true" else null }
        bootstrapOrchestrator(store) { null }

        val tasks = store.list()
        tasks shouldHaveSize 1
        tasks.single().enabled shouldBe false
    }

    test("switch on again after being disabled re-enables the existing task rather than recreating it") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        val on: (String) -> String? = { if (it == ORCHESTRATOR_ENABLED_ENV) "true" else null }
        bootstrapOrchestrator(store, on)
        val originalId = store.list().single().id
        bootstrapOrchestrator(store) { null }
        bootstrapOrchestrator(store, on)

        val tasks = store.list()
        tasks shouldHaveSize 1
        tasks.single().id shouldBe originalId
        tasks.single().enabled shouldBe true
    }
})
