package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContextScopeTest : FunSpec({
    fun p(id: String, root: String, global: Boolean) = ProjectInfo(id, root, root.substringAfterLast('/'), "active", global, 1L, 1, false, emptyList())

    test("bound session searches its project first, then globals; unbound searches only globals") {
        val ps = listOf(p("a", "/x/a", false), p("g2", "/x/notes2", true), p("g1", "/x/notes", true))
        ContextScope.searchIds("a", ps) shouldBe listOf("a", "g1", "g2")
        ContextScope.searchIds(null, ps) shouldBe listOf("g1", "g2")
    }
    test("an unknown or removed primary id is ignored, not searched") {
        ContextScope.searchIds("gone", listOf(p("g", "/x/n", true))) shouldBe listOf("g")
    }
    test("a global primary is not listed twice") {
        ContextScope.searchIds("g", listOf(p("g", "/x/n", true))) shouldBe listOf("g")
    }
})
