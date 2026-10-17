package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class RelationsTest : FunSpec({
    fun src(path: String, lang: String, imports: List<String> = emptyList(), links: List<String> = emptyList(), role: Role = Role.SOURCE) =
        Relations.Source(path, lang, role, imports, links)
    fun imports(files: List<Relations.Source>) = Relations.all(files).filter { it.kind == Relations.IMPORTS }.map { it.from to it.to }

    test("kotlin/java qualified imports resolve by path suffix, member imports by their class; unknown ones are dropped") {
        val main = "app/src/main/kotlin/a/b/Main.kt"
        imports(listOf(
            src(main, "kotlin", listOf("a.c.Util", "a.c.Util.helper", "a.d.Legacy", "kotlinx.coroutines.flow.Flow")),
            src("lib/src/main/kotlin/a/c/Util.kt", "kotlin"),
            src("lib/src/main/java/a/d/Legacy.java", "java"))) shouldContainExactlyInAnyOrder listOf(
            main to "lib/src/main/kotlin/a/c/Util.kt", main to "lib/src/main/java/a/d/Legacy.java")
    }

    test("ts/js relative imports try extensions and index files; bare packages are dropped") {
        imports(listOf(src("web/src/app.ts", "typescript", listOf("./util", "../lib", "react")),
            src("web/src/util.ts", "typescript"), src("web/lib/index.ts", "typescript"))) shouldContainExactlyInAnyOrder listOf(
            "web/src/app.ts" to "web/src/util.ts", "web/src/app.ts" to "web/lib/index.ts")
    }

    test("python absolute and relative imports") {
        imports(listOf(src("pkg/app/main.py", "python", listOf("pkg.core.db", ".helpers", "..shared", "os")),
            src("pkg/core/db.py", "python"), src("pkg/app/helpers.py", "python"), src("pkg/shared/__init__.py", "python"))) shouldContainExactlyInAnyOrder listOf(
            "pkg/app/main.py" to "pkg/core/db.py", "pkg/app/main.py" to "pkg/app/helpers.py", "pkg/app/main.py" to "pkg/shared/__init__.py")
    }

    test("go imports resolve to a file of the package directory; rust crate paths to module files") {
        imports(listOf(src("cmd/main.go", "go", listOf("ex.com/r/internal/store", "fmt")),
            src("internal/store/store.go", "go"), src("internal/store/store_test.go", "go", role = Role.TEST))) shouldContainExactlyInAnyOrder listOf(
            "cmd/main.go" to "internal/store/store.go")
        imports(listOf(src("src/main.rs", "rust", listOf("crate::net::server", "crate::util", "std::io")),
            src("src/net/server.rs", "rust"), src("src/util/mod.rs", "rust"))) shouldContainExactlyInAnyOrder listOf(
            "src/main.rs" to "src/net/server.rs", "src/main.rs" to "src/util/mod.rs")
    }

    test("markdown: wikilinks by note name (nearest wins), relative links by path, self-links dropped") {
        Relations.all(listOf(src("notes/a.md", "markdown", links = listOf("[[B]]", "[[Missing]]", "../doc/c.md", "[[a]]"), role = Role.DOC),
            src("notes/B.md", "markdown", role = Role.DOC), src("archive/old/b.md", "markdown", role = Role.DOC),
            src("doc/c.md", "markdown", role = Role.DOC))).map { Triple(it.from, it.to, it.kind) } shouldContainExactlyInAnyOrder listOf(
            Triple("notes/a.md", "notes/B.md", "LINKS"), Triple("notes/a.md", "doc/c.md", "LINKS"))
    }

    test("python __future__ and image wikilinks resolve to no project file") {
        imports(listOf(src("a.py", "python", listOf("__future__")), src("__future__.pyi", "python"))) shouldContainExactlyInAnyOrder emptyList()
        Relations.all(listOf(src("n/a.md", "markdown", links = listOf("[[img.png]]"), role = Role.DOC),
            src("n/img.md", "markdown", role = Role.DOC))) shouldContainExactlyInAnyOrder emptyList()
    }

    test("tests by naming convention point at the nearest same-language source") {
        Relations.tests(listOf(
            src("core/src/main/kotlin/x/Foo.kt", "kotlin"), src("core/src/test/kotlin/x/FooTest.kt", "kotlin", role = Role.TEST),
            src("other/src/main/kotlin/y/Foo.kt", "kotlin"),
            src("web/src/foo.ts", "typescript"), src("web/src/foo.test.ts", "typescript", role = Role.TEST),
            src("py/bar.py", "python"), src("py/test_bar.py", "python"),
            src("go/baz.go", "go"), src("go/baz_test.go", "go", role = Role.TEST))).map { it.from to it.to } shouldContainExactlyInAnyOrder listOf(
            "core/src/test/kotlin/x/FooTest.kt" to "core/src/main/kotlin/x/Foo.kt", "web/src/foo.test.ts" to "web/src/foo.ts",
            "py/test_bar.py" to "py/bar.py", "go/baz_test.go" to "go/baz.go")
    }
})
