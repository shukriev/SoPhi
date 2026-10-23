package dev.sophi.core.undo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun sh(dir: Path, vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }
    return out
}

class CheckpointStoreTest : FunSpec({
    fun fixture(maxFileBytes: Long = 10_000_000, git: String = "git", timeout: Long = 60): Triple<Path, CheckpointStore, MutableList<String>> {
        val root = createTempDirectory("ckpt-root")
        val warnings = mutableListOf<String>()
        val store = CheckpointStore(root, createTempDirectory("ckpt-home"), maxFileBytes, git, timeout) { warnings += it }
        return Triple(root, store, warnings)
    }

    test("restore brings back edited, deleted and created files in a non-git directory") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        root.resolve("b.txt").writeText("b")
        val c = store.snapshot()!!
        root.resolve("a.txt").writeText("changed")
        root.resolve("b.txt").toFile().delete()
        root.resolve("c.txt").writeText("c")
        store.restore(c)
        root.resolve("a.txt").readText() shouldBe "a"
        root.resolve("b.txt").readText() shouldBe "b"
        root.resolve("c.txt").exists() shouldBe false
    }

    test("restore removes files created after the checkpoint") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve("new.txt").writeText("n")
        store.restore(c)
        root.resolve("new.txt").exists() shouldBe false
    }

    test("the user's own git repo is unchanged") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        sh(root, "git", "init", "-q")
        sh(root, "git", "add", "-A")
        sh(root, "git", "-c", "user.name=u", "-c", "user.email=u@x", "commit", "-q", "-m", "init")
        val head = sh(root, "git", "rev-parse", "HEAD")
        val index = sh(root, "git", "ls-files", "-s")
        val c = store.snapshot()!!
        root.resolve("a.txt").writeText("changed")
        root.resolve("n.txt").writeText("n")
        store.restore(c)
        sh(root, "git", "rev-parse", "HEAD") shouldBe head
        sh(root, "git", "ls-files", "-s") shouldBe index
    }

    test("restore leaves gitignored files alone") {
        val (root, store) = fixture()
        root.resolve(".gitignore").writeText("secret\n")
        root.resolve("secret").writeText("1")
        val c = store.snapshot()!!
        root.resolve("secret").writeText("2")
        store.restore(c)
        root.resolve("secret").readText() shouldBe "2"
    }

    test("files over maxFileBytes are skipped with a warning") {
        val (root, store, warnings) = fixture(maxFileBytes = 10)
        root.resolve("a.txt").writeText("a")
        root.resolve("big.bin").writeText("12345678901")
        val c = store.snapshot()!!
        warnings.any { "big.bin" in it } shouldBe true
        root.resolve("big.bin").writeText("changed again, still big")
        store.diffStat(c) shouldBe ""
    }

    test("a snapshot with no change reuses the previous checkpoint") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe store.snapshot()
    }

    test("diffStat names the changed files") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve("a.txt").writeText("edited")
        store.diffStat(c) shouldContain "a.txt"
    }

    test("snapshot returns null and warns once when git is missing") {
        val (root, store, warnings) = fixture(git = "/nonexistent/git")
        root.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe null
        store.snapshot() shouldBe null
        warnings shouldHaveSize 1
    }

    test("the user's global git config (gpgsign, hooksPath) does not affect snapshots") {
        val dir = createTempDirectory("ckpt-cfg")
        val hooks = dir.resolve("hooks").also { it.toFile().mkdirs() }
        hooks.resolve("pre-commit").also { it.writeText("#!/bin/sh\nexit 1\n"); it.toFile().setExecutable(true) }
        dir.resolve("gitconfig").writeText("[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = /bin/false\n[core]\n\thooksPath = $hooks\n")
        val wrapper = dir.resolve("git-wrapper").also {
            it.writeText("#!/bin/sh\nGIT_CONFIG_GLOBAL=${dir.resolve("gitconfig")} exec git \"\$@\"\n")
            it.toFile().setExecutable(true)
        }
        val (root, store, warnings) = fixture(git = wrapper.toString())
        root.resolve("a.txt").writeText("a")
        (store.snapshot() != null) shouldBe true
        warnings shouldHaveSize 0
    }

    test("restore throws when git fails") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        shouldThrow<IllegalStateException> { store.restore("deadbeef".repeat(5)) }
    }

    test("a hung git is killed after the timeout and snapshot returns null") {
        val dir = createTempDirectory("ckpt-hang")
        val hang = dir.resolve("git-hang").also { it.writeText("#!/bin/sh\nsleep 30\n"); it.toFile().setExecutable(true) }
        val (root, store, warnings) = fixture(git = hang.toString(), timeout = 1)
        root.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe null
        warnings.single() shouldContain "timed out"
    }
})
