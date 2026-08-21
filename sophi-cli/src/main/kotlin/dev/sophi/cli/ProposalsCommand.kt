package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.schedule.store.ProposalStore
import java.nio.file.Path

private fun store(home: Path) = ProposalStore(home.resolve("proposals.jsonl"))
private val defaultHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule")

class ProposalsList(
    private val home: Path,
    private val status: String?,
    private val echo: (String) -> Unit
) {
    fun run() {
        val proposals = store(home).list(status)
        if (proposals.isEmpty()) { echo("No proposals."); return }
        proposals.forEach { echo("${it.id}  [${it.status}] [${it.category}]  ${it.title}") }
    }
}

class ProposalsAccept(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() {
        if (store(home).accept(id)) echo("Accepted $id") else echo("No pending proposal found with id $id")
    }
}

class ProposalsReject(
    private val home: Path,
    private val id: String,
    private val reason: String,
    private val echo: (String) -> Unit
) {
    fun run() {
        if (store(home).reject(id, reason)) echo("Rejected $id") else echo("No pending proposal found with id $id")
    }
}

class ProposalsCommand : CliktCommand(name = "proposals", help = "Inspect and review self-improvement proposals") {
    override fun run() = Unit
}

class ProposalsListCommand : CliktCommand(name = "list") {
    private val status by option("--status", help = "Filter by status: pending | accepted | rejected")
    override fun run() = ProposalsList(defaultHome, status) { echo(it) }.run()
}

class ProposalsAcceptCommand : CliktCommand(name = "accept") {
    private val id by argument()
    override fun run() = ProposalsAccept(defaultHome, id) { echo(it) }.run()
}

class ProposalsRejectCommand : CliktCommand(name = "reject") {
    private val id by argument()
    private val reason by argument()
    override fun run() = ProposalsReject(defaultHome, id, reason) { echo(it) }.run()
}
