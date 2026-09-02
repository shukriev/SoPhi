package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class NotificationStoreTest : FunSpec({
    fun store(maxRecords: Int = 200) = NotificationStore(tempdir().toPath().resolve("notifications.json"), maxRecords)

    test("list returns empty when the file doesn't exist yet") {
        store().list() shouldBe emptyList()
    }

    test("add persists a record and list returns it") {
        val s = store()
        val record = s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "t", body = "b"))
        s.list() shouldBe listOf(record)
    }

    test("add appends in insertion order") {
        val s = store()
        val first = s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "first", body = "b"))
        val second = s.add(NotificationRecord(kind = NotificationKind.Memory, title = "second", body = "b"))
        s.list() shouldBe listOf(first, second)
    }

    test("add drops the oldest record once maxRecords is exceeded") {
        val s = store(maxRecords = 2)
        s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "one", body = "b"))
        val two = s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "two", body = "b"))
        val three = s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "three", body = "b"))
        s.list() shouldBe listOf(two, three)
    }

    test("markAllRead flips read to true on every record, leaving everything else unchanged") {
        val s = store()
        val record = s.add(NotificationRecord(kind = NotificationKind.Confirmation, title = "t", body = "b"))
        record.read shouldBe false

        s.markAllRead()

        val updated = s.list().single()
        updated.read shouldBe true
        updated.id shouldBe record.id
        updated.title shouldBe record.title
    }

    test("clear removes every record") {
        val s = store()
        s.add(NotificationRecord(kind = NotificationKind.Schedule, title = "t", body = "b"))

        s.clear()

        s.list() shouldBe emptyList()
    }
})
