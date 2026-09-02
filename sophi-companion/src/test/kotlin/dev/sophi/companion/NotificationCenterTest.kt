package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class NotificationCenterTest : FunSpec({
    fun center() = NotificationCenter(NotificationStore(tempdir().toPath().resolve("notifications.json")))

    test("records starts empty for a fresh store") {
        center().records.value shouldBe emptyList()
    }

    test("add publishes the new record to records") {
        val c = center()
        val record = c.add(NotificationKind.Schedule, "t", "b")
        c.records.value shouldBe listOf(record)
    }

    test("records is newest first") {
        val c = center()
        val first = c.add(NotificationKind.Schedule, "first", "b")
        val second = c.add(NotificationKind.Memory, "second", "b")
        c.records.value shouldBe listOf(second, first)
    }

    test("markAllRead flips every record's read flag to true") {
        val c = center()
        c.add(NotificationKind.Confirmation, "t", "b")
        c.records.value.single().read shouldBe false

        c.markAllRead()

        c.records.value.single().read shouldBe true
    }

    test("records reflects what was already on disk when constructed") {
        val store = NotificationStore(tempdir().toPath().resolve("notifications.json"))
        val existing = store.add(NotificationRecord(kind = NotificationKind.Schedule, title = "t", body = "b"))

        val c = NotificationCenter(store)

        c.records.value shouldBe listOf(existing)
    }

    test("clear empties records and persists the clear") {
        val c = center()
        c.add(NotificationKind.Schedule, "t", "b")

        c.clear()

        c.records.value shouldBe emptyList()
    }
})
