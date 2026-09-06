package dev.sophi.cli

import dev.sophi.core.tools.RunClaudeCodeTool
import dev.sophi.schedule.model.Proposal
import dev.sophi.schedule.store.ProposalStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private fun runGit(dir: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: ${p.inputStream.bufferedReader().readText()}" }
}

private fun fixtureRepo(dir: Path): Path {
    Files.createDirectories(dir)
    val repo = dir.toFile()
    runGit(repo, "init", "-q", "-b", "main")
    runGit(repo, "config", "user.email", "test@example.invalid")
    runGit(repo, "config", "user.name", "Test")
    dir.resolve("README.md").writeText("hello\n")
    runGit(repo, "add", "README.md")
    runGit(repo, "commit", "-q", "-m", "initial")

    // A real local bare repo as "origin" so `git push` in the happy-path test succeeds without
    // any network round-trip -- a fake https:// remote would make push genuinely fail.
    val bareRemote = dir.resolveSibling(dir.fileName.toString() + "-origin.git").toFile()
    ProcessBuilder(listOf("git", "init", "-q", "--bare", bareRemote.toString())).start().waitFor()
    runGit(repo, "remote", "add", "origin", bareRemote.toString())
    return dir
}

private fun fakeExecutable(dir: Path, name: String, body: String): List<String> {
    val script = dir.resolve(name)
    script.writeText("#!/bin/sh\n$body\n")
    script.toFile().setExecutable(true)
    return listOf(script.toString())
}

class ProposalImplementCommandTest : FunSpec({
    test("errors and does nothing when the kill switch is off") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "T", category = "process", rationale = "r", suggestedAction = "x"))
        store.accept(p.id)
        val messages = mutableListOf<String>()

        ProposalImplement(
            proposalId = p.id, store = store, enabled = false,
            repoRoot = tempdir().toPath(), worktreesParent = tempdir().toPath(),
            claudeCodeTool = RunClaudeCodeTool(), confirm = { true },
            echo = { messages.add(it) }
        ).run()

        messages.any { it.contains("SOPHI_SHADOW_PR_ENABLED") } shouldBe true
        store.get(p.id)!!.status shouldBe "accepted"
    }

    test("errors when the proposal is not accepted") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "T", category = "process", rationale = "r", suggestedAction = "x"))
        val messages = mutableListOf<String>()

        ProposalImplement(
            proposalId = p.id, store = store, enabled = true,
            repoRoot = tempdir().toPath(), worktreesParent = tempdir().toPath(),
            claudeCodeTool = RunClaudeCodeTool(), confirm = { true },
            echo = { messages.add(it) }
        ).run()

        messages.any { it.contains("not accepted") } shouldBe true
    }

    test("full happy path: opens a PR and marks the proposal pr-opened") {
        val scratch = tempdir().toPath()
        val repo = fixtureRepo(scratch.resolve("repo"))
        val store = ProposalStore(scratch.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "Fix Thing", category = "process", rationale = "r", suggestedAction = "change src.txt"))
        store.accept(p.id)

        val claudeCmd = fakeExecutable(scratch, "fake-claude.sh", "echo change >> src.txt\ngit add src.txt\ngit commit -q -m change\necho '{\"result\":\"done\"}'")
        val mvnCmd = fakeExecutable(scratch, "fake-mvn.sh", "mkdir -p sophi-cli/target && touch sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar")
        val ghCmd = fakeExecutable(scratch, "fake-gh.sh", "echo https://example.invalid/pull/1")

        val messages = mutableListOf<String>()
        ProposalImplement(
            proposalId = p.id, store = store, enabled = true,
            repoRoot = repo, worktreesParent = scratch.resolve("worktrees"),
            claudeCodeTool = RunClaudeCodeTool(claudeCommand = claudeCmd),
            gitCommand = listOf("git"), mvnCommand = mvnCmd, ghCommand = ghCmd,
            evalsDirOverride = scratch.resolve("no-such-evals-dir"),
            confirm = { true }, echo = { messages.add(it) }
        ).run()

        store.get(p.id)!!.status shouldBe "pr-opened"
        store.get(p.id)!!.implementedDetail shouldBe "https://example.invalid/pull/1"
    }

    test("diff-scan empty diff aborts to implementation-failed with no build attempted") {
        val scratch = tempdir().toPath()
        val repo = fixtureRepo(scratch.resolve("repo"))
        val store = ProposalStore(scratch.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "No-op", category = "process", rationale = "r", suggestedAction = "do nothing"))
        store.accept(p.id)

        val claudeCmd = fakeExecutable(scratch, "fake-claude.sh", "echo '{\"result\":\"nothing to do\"}'")
        val mvnCmd = fakeExecutable(scratch, "fake-mvn.sh", "echo SHOULD_NOT_RUN > mvn-was-called.txt\nexit 1")

        ProposalImplement(
            proposalId = p.id, store = store, enabled = true,
            repoRoot = repo, worktreesParent = scratch.resolve("worktrees"),
            claudeCodeTool = RunClaudeCodeTool(claudeCommand = claudeCmd),
            gitCommand = listOf("git"), mvnCommand = mvnCmd, ghCommand = listOf("gh"),
            confirm = { true }, echo = { }
        ).run()

        store.get(p.id)!!.status shouldBe "implementation-failed"
        scratch.resolve("worktrees").resolve(p.id).resolve("mvn-was-called.txt").toFile().exists() shouldBe false
    }
})
