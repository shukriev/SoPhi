package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class AnalyzersTest : FunSpec({
    test("kotlin: one chunk per top-level declaration, labelled, preamble kept") {
        val src = """
            package a

            /** Compacts sessions. */
            class ContextCompactor {
                fun compact() = 1
            }

            fun helper() = 2
        """.trimIndent()
        val a = Analyzers.analyze("a/ContextCompactor.kt", Role.SOURCE, "kotlin", src)
        a.chunks.map { it.label } shouldContainExactly listOf(null, "ContextCompactor", "helper")
        a.chunks[1].startLine shouldBe 3   // KDoc belongs to the declaration below it
        a.summary shouldBe "Compacts sessions."

        val forms = """
            private suspend fun <T> gen(x: T) = x
            fun String.ext() = 1
            fun <T> List<T>.gext() = 1
            fun interface Handler { fun go() }
            data object Nothing1
            sealed interface Ev
            enum class Color { R }
        """.trimIndent()
        Analyzers.analyze("F.kt", Role.SOURCE, "kotlin", forms).chunks.map { it.label } shouldContainExactly
            listOf("gen", "ext", "gext", "Handler", "Nothing1", "Ev", "Color")
    }

    test("markdown: one section per heading, summary is the first paragraph") {
        val md = "# Title\n\nFirst paragraph here.\n\n## Part\ntext\n"
        val a = Analyzers.analyze("README.md", Role.DOC, "markdown", md)
        a.chunks.map { it.label } shouldContainExactly listOf("Title", "Part")
        a.summary shouldBe "First paragraph here."
    }

    test("unknown language falls back to 60-line windows stepping 50") {
        val text = (1..130).joinToString("\n") { "line $it" }
        Analyzers.analyze("x.txt", Role.DOC, "text", text).chunks.map { it.startLine to it.endLine } shouldContainExactly
            listOf(1 to 60, 51 to 110, 101 to 130)
    }

    test("generated files produce no chunks") {
        Analyzers.analyze("package-lock.json", Role.GENERATED, "json", "{}").chunks shouldBe emptyList()
    }

    test("pom modules and gradle includes become project facts") {
        Analyzers.analyze("pom.xml", Role.CONFIG, "xml", "<modules><module>sophi-ai</module><module>sophi-core</module></modules>").modules shouldContainExactly listOf("sophi-ai", "sophi-core")
        Analyzers.analyze("settings.gradle.kts", Role.CONFIG, "kotlin", "include(\":app\", \":lib\")\ninclude 'tools'").modules shouldContainExactly listOf("app", "lib", "tools")
    }

    test("an oversized declaration is windowed but keeps its label") {
        val body = (1..200).joinToString("\n") { "    val x$it = $it" }
        val a = Analyzers.analyze("Big.kt", Role.SOURCE, "kotlin", "class Big {\n$body\n}")
        (a.chunks.size > 1) shouldBe true
        a.chunks.all { it.label == "Big" } shouldBe true
    }
})
