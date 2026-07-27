package dev.sophi.calendar.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UnsupportedCalendarProviderTest : FunSpec({
    test("every operation throws with a clear 'no calendar backend' message") {
        val provider = UnsupportedCalendarProvider
        val ex1 = runCatching { provider.listCalendars() }.exceptionOrNull()
        ex1?.message shouldBe "No calendar backend available on this OS"

        val ex2 = runCatching {
            provider.create(dev.sophi.calendar.model.CalendarEvent(title = "x", start = 1L, end = 2L))
        }.exceptionOrNull()
        ex2?.message shouldBe "No calendar backend available on this OS"

        val ex3 = runCatching { provider.get("id", null) }.exceptionOrNull()
        ex3?.message shouldBe "No calendar backend available on this OS"

        val ex4 = runCatching { provider.list(null, 0L, 1L) }.exceptionOrNull()
        ex4?.message shouldBe "No calendar backend available on this OS"

        val ex5 = runCatching {
            provider.update("id", null, dev.sophi.calendar.model.CalendarEventPatch())
        }.exceptionOrNull()
        ex5?.message shouldBe "No calendar backend available on this OS"

        val ex6 = runCatching { provider.delete("id", null) }.exceptionOrNull()
        ex6?.message shouldBe "No calendar backend available on this OS"
    }
})
