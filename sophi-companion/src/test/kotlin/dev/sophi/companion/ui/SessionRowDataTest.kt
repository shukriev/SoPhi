package dev.sophi.companion.ui

import dev.sophi.companion.SessionState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SessionRowDataTest : FunSpec({
    test("rows needing attention (Error, NeedsConfirmation) sort before Running and Idle") {
        val idle = SessionRowData("a", "Alpha", false, SessionState.Idle, lastActiveMillis = 100)
        val running = SessionRowData("b", "Bravo", false, SessionState.Running, lastActiveMillis = 100)
        val error = SessionRowData("c", "Charlie", false, SessionState.Error("boom"), lastActiveMillis = 100)
        val needsConfirm = SessionRowData("d", "Delta", false, SessionState.NeedsConfirmation(emptyList()), lastActiveMillis = 100)

        val sorted = sortForSidebar(listOf(idle, running, error, needsConfirm))

        sorted.map { it.id } shouldBe listOf("c", "d", "b", "a")
    }

    test("within the same urgency, most recently active sorts first") {
        val old = SessionRowData("old", "Old", false, SessionState.Idle, lastActiveMillis = 100)
        val newest = SessionRowData("new", "New", false, SessionState.Idle, lastActiveMillis = 300)
        val mid = SessionRowData("mid", "Mid", false, SessionState.Idle, lastActiveMillis = 200)

        val sorted = sortForSidebar(listOf(old, newest, mid))

        sorted.map { it.id } shouldBe listOf("new", "mid", "old")
    }

    test("Error and NeedsConfirmation rows interleave by recency, both ranked above Running/Idle") {
        val error = SessionRowData("e", "Zulu-error", false, SessionState.Error("x"), lastActiveMillis = 100)
        val needsConfirm = SessionRowData("n", "Alpha-confirm", false, SessionState.NeedsConfirmation(emptyList()), lastActiveMillis = 200)
        val running = SessionRowData("r", "Aaa-running", false, SessionState.Running, lastActiveMillis = 300)

        val sorted = sortForSidebar(listOf(error, needsConfirm, running))

        sorted.map { it.id } shouldBe listOf("n", "e", "r")
    }

    test("ties in recency within the same urgency fall back to alphabetical by title") {
        val zebra = SessionRowData("z", "Zebra", false, SessionState.Idle, lastActiveMillis = 100)
        val apple = SessionRowData("a", "Apple", false, SessionState.Idle, lastActiveMillis = 100)

        val sorted = sortForSidebar(listOf(zebra, apple))

        sorted.map { it.id } shouldBe listOf("a", "z")
    }

    test("empty list sorts to an empty list") {
        sortForSidebar(emptyList()) shouldBe emptyList()
    }
})
