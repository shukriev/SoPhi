package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ProjectFilesTest : FunSpec({
    fun Path.write(rel: String, text: String = "x") = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    test("walk skips default dirs, .gitignore patterns, binaries and big files") {
        val root = tempdir().toPath()
        root.write("src/Main.kt", "fun main() {}")
        root.write("node_modules/x/index.js"); root.write("build/out.txt"); root.write(".git/HEAD")
        root.write(".gitignore", "*.log\nsecret/\n"); root.write("app.log"); root.write("secret/key.txt")
        Files.write(root.resolve("img.png"), byteArrayOf(1, 0, 2))
        Files.write(root.resolve("big.txt"), ByteArray((ProjectFiles.MAX_BYTES + 1).toInt()) { 'a'.code.toByte() })
        ProjectFiles.list(root, GitProbe.none).files.map { it.relPath } shouldContainExactlyInAnyOrder listOf("src/Main.kt", ".gitignore")
    }

    test("git listing is used when available, still filtered for size and binaries") {
        val root = tempdir().toPath()
        root.write("a.kt"); Files.write(root.resolve("b.bin"), byteArrayOf(0, 1))
        val git = GitProbe { listOf("a.kt", "b.bin", "gone.kt") }   // gone.kt: tracked but deleted
        ProjectFiles.list(root, git).files.map { it.relPath } shouldBe listOf("a.kt")
    }

    test("classifies roles and languages") {
        ProjectFiles.classify("sophi-core/src/main/kotlin/A.kt") shouldBe (Role.SOURCE to "kotlin")
        ProjectFiles.classify("sophi-core/src/test/kotlin/ATest.kt") shouldBe (Role.TEST to "kotlin")
        ProjectFiles.classify("doc/adr/ADR-001.md") shouldBe (Role.DOC to "markdown")
        ProjectFiles.classify("AGENTS.md") shouldBe (Role.INSTRUCTION to "markdown")
        ProjectFiles.classify("sub/.sophi/instructions.md") shouldBe (Role.INSTRUCTION to "markdown")
        ProjectFiles.classify("pom.xml") shouldBe (Role.CONFIG to "xml")
        ProjectFiles.classify("package-lock.json") shouldBe (Role.GENERATED to "json")
        ProjectFiles.classify("web/app.min.js") shouldBe (Role.GENERATED to "javascript")
    }

    test("caps the listing and marks it partial") {
        val root = tempdir().toPath()
        repeat(5) { root.write("f$it.txt") }
        ProjectFiles.list(root, GitProbe.none, maxFiles = 3).let { it.files.size shouldBe 3; it.partial shouldBe true }
    }

    test("findGitRoot walks up to the folder holding .git") {
        val root = tempdir().toPath(); Files.createDirectories(root.resolve(".git")); Files.createDirectories(root.resolve("a/b"))
        findGitRoot(root.resolve("a/b")) shouldBe root
        findGitRoot(tempdir().toPath()) shouldBe null
    }

    test("symlinks are never listed, on the walk or the git path") {
        val root = tempdir().toPath(); val outside = tempdir().toPath().write("secret.txt", "s")
        root.write("ok.txt"); Files.createSymbolicLink(root.resolve("link.txt"), outside)
        ProjectFiles.list(root, GitProbe.none).files.map { it.relPath } shouldBe listOf("ok.txt")
        ProjectFiles.list(root, GitProbe { listOf("link.txt", "ok.txt") }).files.map { it.relPath } shouldBe listOf("ok.txt")
    }

    test("secret-looking files are never listed") {
        val root = tempdir().toPath()
        listOf(".env", ".env.local", "server.pem", "id_rsa", "a.kt").forEach { root.write(it) }
        ProjectFiles.list(root, GitProbe.none).files.map { it.relPath } shouldBe listOf("a.kt")
        ProjectFiles.list(root, GitProbe { listOf(".env", ".env.local", "server.pem", "id_rsa", "a.kt") }).files.map { it.relPath } shouldBe listOf("a.kt")
    }

    test("instruction match needs a slash boundary") {
        ProjectFiles.classify("foo.sophi/instructions.md").first shouldBe Role.DOC
        ProjectFiles.classify("sub/.sophi/instructions.md").first shouldBe Role.INSTRUCTION
    }

    test("binary and oversized files do not count toward the walk cap") {
        val root = tempdir().toPath()
        repeat(5) { Files.write(root.resolve("b$it.bin"), byteArrayOf(0, 1)) }
        repeat(3) { root.write("t$it.txt") }
        ProjectFiles.list(root, GitProbe.none, maxFiles = 3).let { it.files.size shouldBe 3; it.partial shouldBe false }
        root.write("t3.txt")
        ProjectFiles.list(root, GitProbe.none, maxFiles = 3).let { it.files.size shouldBe 3; it.partial shouldBe true }
    }
})
