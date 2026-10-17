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

    test("summary skips licence headers and prefers the doc above the first declaration") {
        val src = "/**\n * Copyright 2026 Acme\n */\npackage a\n\nval s = \"\"\"x\"\"\"\n/** Real summary. */\nclass X {\n    /** member */\n    fun f() = 1\n}\n"
        Analyzers.analyze("X.kt", Role.SOURCE, "kotlin", src).summary shouldBe "Real summary."
    }

    test("empty, trailing newline, giant line, annotated one-liners, gradle edge cases") {
        Analyzers.analyze("A.kt", Role.SOURCE, "kotlin", "class A\n").chunks.map { it.endLine } shouldContainExactly listOf(1)
        Analyzers.analyze("A.kt", Role.SOURCE, "kotlin", "").chunks shouldBe emptyList()
        Analyzers.analyze("m.txt", Role.DOC, "text", "x".repeat(20_000)).chunks.size shouldBe 3
        Analyzers.analyze("G.kt", Role.SOURCE, "kotlin", "@Serializable private data class GrepArgs(val a: Int)").chunks.map { it.label } shouldContainExactly listOf("GrepArgs")
        Analyzers.analyze("E.java", Role.SOURCE, "java", "@Entity public class E {}").chunks.map { it.label } shouldContainExactly listOf("E")
        Analyzers.analyze("settings.gradle.kts", Role.CONFIG, "kotlin", "includeBuild(\"../x\")\ninclude(\n\":a\",\n\":b\"\n)").modules shouldContainExactly listOf("a", "b")
    }

    test("declarations become symbols with kind and 1-based line") {
        val src = "package a\n\nimport b.c.D\n\n/** Doc. */\ndata class Foo(val x: Int)\n\nfun bar() = 1\nval type = 2\n"
        val a = Analyzers.analyze("a/Foo.kt", Role.SOURCE, "kotlin", src)
        a.symbols shouldContainExactly listOf(SymbolDraft("Foo", "class", 6), SymbolDraft("bar", "fun", 8), SymbolDraft("type", "val", 9))
        a.imports shouldContainExactly listOf("b.c.D")
        Analyzers.analyze("x.go", Role.SOURCE, "go", "package x\n\nfunc (s *S) Run() {}\ntype S struct{}\n").symbols shouldContainExactly
            listOf(SymbolDraft("Run", "func", 3), SymbolDraft("S", "type", 4))
        Analyzers.analyze("README.md", Role.DOC, "markdown", "# T\n\ntext\n").symbols shouldBe emptyList()
    }

    test("imports per family; wildcards dropped, external packages kept raw for the resolver") {
        Analyzers.analyze("A.java", Role.SOURCE, "java", "import java.util.List;\nimport static a.b.C.d;\nimport a.b.*;\nclass A {}\n").imports shouldContainExactly
            listOf("java.util.List", "a.b.C.d")
        Analyzers.analyze("a.ts", Role.SOURCE, "typescript",
            "import { x } from './x'\nimport type { Y } from \"../y/y\"\nimport './side'\nconst z = require('./z')\nexport * from './w'\n").imports shouldContainExactly
            listOf("./x", "../y/y", "./side", "./z", "./w")
        Analyzers.analyze("p/m.py", Role.SOURCE, "python", "import os, a.b as c\nfrom .sib import f\nfrom . import u, v\nfrom pkg.mod import g\n").imports shouldContainExactly
            listOf("os", "a.b", ".sib", ".u", ".v", "pkg.mod")
        Analyzers.analyze("m.go", Role.SOURCE, "go", "package m\n\nimport \"fmt\"\nimport (\n\t\"ex.com/r/pkg/util\"\n\tal \"ex.com/r/pkg/log\"\n)\n").imports shouldContainExactly
            listOf("fmt", "ex.com/r/pkg/util", "ex.com/r/pkg/log")
        Analyzers.analyze("src/main.rs", Role.SOURCE, "rust", "use crate::net::server;\npub use crate::util::{a, b};\nuse std::io;\n").imports shouldContainExactly
            listOf("crate::net::server", "crate::util", "std::io")
    }

    test("markdown: wikilinks and relative .md links, never URLs or non-markdown targets") {
        val md = "# T\n\nSee [[Other Note]] and [[folder/Deep|alias]] and [[H#sec]].\n[a](../doc/x.md#part) [b](https://e.com/y.md) [c](img.png) [d](./z.md)\n"
        Analyzers.analyze("notes/t.md", Role.DOC, "markdown", md).links shouldContainExactly
            listOf("[[Other Note]]", "[[folder/Deep]]", "[[H]]", "../doc/x.md", "./z.md")
    }
})
