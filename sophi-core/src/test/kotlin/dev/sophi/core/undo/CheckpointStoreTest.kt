package dev.sophi.core.undo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
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

    test("restore after .gitignore was edited keeps ignored files") {
        val (root, store) = fixture()
        root.resolve(".gitignore").writeText("secret\nnode_modules/\n")
        root.resolve("secret").writeText("s")
        root.resolve("node_modules").createDirectories().resolve("x.js").writeText("m")
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve(".gitignore").writeText("other\n")
        root.resolve("a.txt").writeText("changed")
        root.resolve("new.txt").writeText("n")
        store.restore(c)
        root.resolve(".gitignore").readText() shouldBe "secret\nnode_modules/\n"
        root.resolve("a.txt").readText() shouldBe "a"
        root.resolve("new.txt").exists() shouldBe false
        root.resolve("secret").readText() shouldBe "s"
        root.resolve("node_modules/x.js").readText() shouldBe "m"
    }

    test("restore keeps a file ignored only by the current rules") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve("tracked-then-ignored").writeText("t")
        store.snapshot()
        root.resolve(".gitignore").writeText("tracked-then-ignored\n")
        store.restore(c)
        root.resolve("tracked-then-ignored").readText() shouldBe "t"
    }

    test("restore leaves the kept paths alone and returns the pre-restore snapshot") {
        val (root, store) = fixture()
        root.resolve("doc").writeText("old")
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve("doc").writeText("new")
        root.resolve("a.txt").writeText("changed")
        val before = store.restore(c, listOf(root.resolve("doc")))
        root.resolve("doc").readText() shouldBe "new"
        root.resolve("a.txt").readText() shouldBe "a"
        store.restore(before)
        root.resolve("a.txt").readText() shouldBe "changed"
    }

    test("diffStat reads in the restore direction: a file the restore deletes shows as a delete") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        val c = store.snapshot()!!
        root.resolve("new.txt").writeText("n")
        store.diffStat(c) shouldContain "delete mode 100644 new.txt"
    }

    test("a root equal to the home gives a null snapshot and one warning") {
        val home = createTempDirectory("ckpt-fakehome")
        val warnings = mutableListOf<String>()
        val store = CheckpointStore(home, home.resolve(".sophi/checkpoints"), home = home) { warnings += it }
        home.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe null
        store.snapshot() shouldBe null
        warnings shouldHaveSize 1
        home.resolve(".sophi").exists() shouldBe false
    }

    test("a root containing the checkpoints home is refused") {
        val root = createTempDirectory("ckpt-root")
        val warnings = mutableListOf<String>()
        val store = CheckpointStore(root, root.resolve("data/checkpoints"), home = createTempDirectory("ckpt-fakehome")) { warnings += it }
        root.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe null
        shouldThrow<IllegalStateException> { store.restore("deadbeef".repeat(5)) }
        warnings shouldHaveSize 1
        root.resolve("data").exists() shouldBe false
    }

    test("a root containing the sophi home is refused") {
        val root = createTempDirectory("ckpt-root")
        val store = CheckpointStore(root, createTempDirectory("ckpt-home"), sophiHome = root.resolve("sub/.sophi"), onWarning = {})
        root.resolve("a.txt").writeText("a")
        store.snapshot() shouldBe null
    }

    test("a root inside a git repo honours the repo's ignore rules and leaves the repo alone") {
        val repo = createTempDirectory("ckpt-repo")
        sh(repo, "git", "init", "-q")
        repo.resolve(".gitignore").writeText(".env\n")
        repo.resolve(".git/info/exclude").writeText("local.secret\n")
        val root = repo.resolve("app").createDirectories()
        root.resolve(".env").writeText("TOKEN=1")
        root.resolve("local.secret").writeText("x")
        root.resolve("a.txt").writeText("a")
        val status = sh(repo, "git", "status", "--porcelain", "--ignored")
        val home = createTempDirectory("ckpt-home")
        val store = CheckpointStore(root, home)
        val c = store.snapshot()!!
        sh(home.toFile().listFiles()!!.single().toPath(), "git", "--git-dir=.", "ls-tree", "-r", "--name-only", c) shouldBe "a.txt\n"
        root.resolve("a.txt").writeText("changed")
        store.restore(c)
        root.resolve(".env").readText() shouldBe "TOKEN=1"
        root.resolve("local.secret").readText() shouldBe "x"
        root.resolve("a.txt").readText() shouldBe "a"
        sh(repo, "git", "status", "--porcelain", "--ignored") shouldBe status
    }

    test("nestedRepos names a nested git repo") {
        val (root, store) = fixture()
        root.resolve("a.txt").writeText("a")
        val nested = root.resolve("vendor/lib").createDirectories()
        sh(nested, "git", "init", "-q")
        nested.resolve("f").writeText("f")
        sh(nested, "git", "add", "-A")
        sh(nested, "git", "-c", "user.name=u", "-c", "user.email=u@x", "commit", "-q", "-m", "init")
        val c = store.snapshot()!!
        store.diffStat(c)
        store.nestedRepos(c) shouldBe listOf("vendor/lib")
    }
})
