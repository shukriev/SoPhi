package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import dev.sophi.cli.selfmod.DiffScanResult
import dev.sophi.cli.selfmod.PrOpener
import dev.sophi.cli.selfmod.ShadowPrAuditEntry
import dev.sophi.cli.selfmod.ShadowPrAuditLog
import dev.sophi.cli.selfmod.ShadowPrWorktreeManager
import dev.sophi.cli.selfmod.runBuild
import dev.sophi.cli.selfmod.runEvalGate
import dev.sophi.cli.selfmod.scanDiff
import dev.sophi.core.tools.RunClaudeCodeTool
import dev.sophi.schedule.store.ProposalStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private val defaultWorktreesParent: Path = Path.of(System.getProperty("user.home"), ".sophi", "selfmod", "worktrees")
private val defaultAuditLogPath: Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule", "shadow-pr-audit.jsonl")
private val defaultRepoRoot: Path = Path.of(System.getProperty("user.dir"))

class ProposalImplement(
    private val proposalId: String,
    private val store: ProposalStore,
    private val enabled: Boolean,
    private val repoRoot: Path,
    private val worktreesParent: Path,
    private val claudeCodeTool: RunClaudeCodeTool,
    private val gitCommand: List<String> = listOf("git"),
    private val mvnCommand: List<String> = listOf("mvn"),
    private val ghCommand: List<String> = listOf("gh"),
    private val javaCommand: List<String> = listOf("java"),
    private val evalsDirOverride: Path? = null,
    private val configVersionId: String = "default",
    private val versioningHome: Path = defaultVersioningHome,
    private val model: String = "claude-3-5-sonnet-20241022",
    private val providerType: String = "claude",
    private val apiKeyOption: String? = null,
    private val baseUrl: String? = null,
    private val maxChangedLines: Int = 500,
    private val budgetMinutes: Long = 20,
    private val auditLog: ShadowPrAuditLog = ShadowPrAuditLog(defaultAuditLogPath),
    private val confirm: (String) -> Boolean,
    private val echo: (String) -> Unit
) {
    private val startMs = System.currentTimeMillis()
    private fun budgetExceeded(): Boolean = System.currentTimeMillis() - startMs > budgetMinutes * 60_000

    fun run() {
        if (!enabled) {
            echo("SOPHI_SHADOW_PR_ENABLED is not set to true -- refusing to run.")
            return
        }
        val proposal = store.get(proposalId)
        if (proposal == null || proposal.status != "accepted") {
            echo("Proposal $proposalId is not accepted (or does not exist) -- nothing to implement.")
            return
        }

        val worktreeManager = ShadowPrWorktreeManager(repoRoot, gitCommand)
        var worktreePath: Path = worktreesParent
        var branch = ""
        try {
            val branchPointSha = worktreeManager.branchPointSha()
            val worktree = worktreeManager.create(worktreesParent, proposalId, proposal.title, branchPointSha)
            worktreePath = worktree.path
            branch = worktree.branch

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            if (!confirm("Run RunClaudeCodeTool against ${worktree.path} for proposal $proposalId?")) {
                return fail(proposal.id, worktree.path, branch, "coding step not confirmed")
            }
            val codingArgs = """{"project_path":"${worktree.path}","task":${jsonQuote(proposal.title + " -- " + proposal.rationale + " -- " + proposal.suggestedAction)}}"""
            val codingResult = runBlocking { claudeCodeTool.execute(codingArgs) }
            if (codingResult.startsWith("Error:")) return fail(proposal.id, worktree.path, branch, "coding step failed: $codingResult")

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            when (val scan = scanDiff(worktree.path, branchPointSha, maxChangedLines, gitCommand)) {
                is DiffScanResult.Empty -> return fail(proposal.id, worktree.path, branch, "diff-scan: no changes made")
                is DiffScanResult.Denylisted -> return fail(proposal.id, worktree.path, branch, "diff-scan: touches denylisted path ${scan.path}")
                is DiffScanResult.TooLarge -> return fail(proposal.id, worktree.path, branch, "diff-scan: ${scan.changedLines} changed lines exceeds cap ${scan.cap}")
                DiffScanResult.Pass -> Unit
            }

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            val challengerBuild = runBuild(worktree.path, skipTests = false, mvnCommand = mvnCommand)
            if (!challengerBuild.success) return fail(proposal.id, worktree.path, branch, "build/test failed:\n${challengerBuild.logTail}")

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            val baselinePath = worktreeManager.createDetached(worktreesParent, branchPointSha, "$proposalId-baseline")
            val baselineBuild = try {
                runBuild(baselinePath, skipTests = true, mvnCommand = mvnCommand)
            } finally {
                worktreeManager.remove(baselinePath)
            }
            if (!baselineBuild.success) return fail(proposal.id, worktree.path, branch, "baseline build failed:\n${baselineBuild.logTail}")

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            val evalOutcome = runEvalGate(
                baselineJar = baselineBuild.jarPath!!, challengerJar = challengerBuild.jarPath!!,
                evalsDir = evalsDirOverride ?: repoRoot.resolve("evals"), configVersionId = configVersionId,
                versioningHome = versioningHome, model = model, providerType = providerType,
                apiKeyOption = apiKeyOption, baseUrl = baseUrl, javaCommand = javaCommand
            )
            if (!evalOutcome.skipped && evalOutcome.result?.accepted == false) {
                return fail(
                    proposal.id, worktree.path, branch, "eval-tournament: ${evalOutcome.result.reason}",
                    evalOutcome.baselineScores, evalOutcome.challengerScores
                )
            }

            if (budgetExceeded()) return fail(proposal.id, worktree.path, branch, "timeout")
            if (!confirm("Open a PR for proposal $proposalId on branch $branch?")) {
                return fail(proposal.id, worktree.path, branch, "PR-open not confirmed")
            }
            val opener = PrOpener(gitCommand, ghCommand)
            if (!opener.push(worktree.path, branch)) return fail(proposal.id, worktree.path, branch, "git push failed")
            val prResult = opener.openPr(
                worktree.path,
                title = "[self-improvement] ${proposal.title}",
                body = "${proposal.rationale}\n\n${proposal.suggestedAction}"
            )
            if (!prResult.success) return fail(proposal.id, worktree.path, branch, "gh pr create failed:\n${prResult.log}")

            store.markPrOpened(proposal.id, prResult.prUrl!!)
            auditLog.record(
                ShadowPrAuditEntry(
                    proposalId = proposal.id, worktreePath = worktree.path.toString(), branch = branch,
                    decision = "pr-opened", reason = "all gates passed", prUrl = prResult.prUrl,
                    baselineScores = evalOutcome.baselineScores, challengerScores = evalOutcome.challengerScores
                )
            )
            echo("Opened ${prResult.prUrl}")
        } catch (e: Exception) {
            fail(proposal.id, worktreePath, branch, e.message ?: "unexpected error")
        }
    }

    private fun jsonQuote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fail(
        proposalId: String, worktreePath: Path, branch: String, reason: String,
        baselineScores: Map<String, List<Double>> = emptyMap(), challengerScores: Map<String, List<Double>> = emptyMap()
    ) {
        store.markImplementationFailed(proposalId, reason)
        auditLog.record(
            ShadowPrAuditEntry(
                proposalId = proposalId, worktreePath = worktreePath.toString(), branch = branch,
                decision = "implementation-failed", reason = reason,
                baselineScores = baselineScores, challengerScores = challengerScores
            )
        )
        echo("Implementation failed for $proposalId: $reason")
    }
}

class ProposalImplementCommand : CliktCommand(name = "implement", help = "Implement an accepted proposal as a shadow PR") {
    private val id: String by argument()
    private val budgetMinutes: Long by option("--budget-minutes").long().default(20)
    private val model: String by option("--model", "-m").default("claude-3-5-sonnet-20241022")
    private val providerType: String by option("--provider").default("claude")
    private val baseUrl: String? by option("--base-url")
    private val apiKeyOption: String? by option("--api-key")
    private val evalsDirStr: String by option("--evals-dir").default("evals")
    private val configVersion: String by option("--config-version").default("default")
    private val versioningHomeStr: String by option("--versioning-home").default(defaultVersioningHome.toString())
    private val worktreesParentStr: String by option("--worktrees-parent").default(defaultWorktreesParent.toString())

    override fun run() {
        val enabled = System.getenv("SOPHI_SHADOW_PR_ENABLED")?.lowercase() == "true"
        val repoRoot = defaultRepoRoot
        val store = ProposalStore(Path.of(System.getProperty("user.home"), ".sophi", "schedule", "proposals.jsonl"))
        ProposalImplement(
            proposalId = id, store = store, enabled = enabled, repoRoot = repoRoot,
            worktreesParent = Path.of(worktreesParentStr), claudeCodeTool = RunClaudeCodeTool(),
            evalsDirOverride = repoRoot.resolve(evalsDirStr), configVersionId = configVersion,
            versioningHome = Path.of(versioningHomeStr), model = model, providerType = providerType,
            apiKeyOption = apiKeyOption, baseUrl = baseUrl, budgetMinutes = budgetMinutes,
            confirm = {
                echo("$it [y/N] ", trailingNewline = false)
                readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true
            },
            echo = { echo(it) }
        ).run()
    }
}

class ProposalCleanup(private val worktreesParent: Path, private val proposalId: String, private val repoRoot: Path, private val echo: (String) -> Unit) {
    fun run() {
        ShadowPrWorktreeManager(repoRoot).remove(worktreesParent.resolve(proposalId))
        echo("Removed worktree for $proposalId")
    }
}

class ProposalCleanupCommand : CliktCommand(name = "cleanup", help = "Remove a shadow-PR worktree") {
    private val id: String by argument()
    private val worktreesParentStr: String by option("--worktrees-parent").default(defaultWorktreesParent.toString())

    override fun run() = ProposalCleanup(Path.of(worktreesParentStr), id, defaultRepoRoot) { echo(it) }.run()
}
