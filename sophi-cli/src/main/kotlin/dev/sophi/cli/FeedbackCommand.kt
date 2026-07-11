package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.PreferenceStore
import java.nio.file.Path

private fun prefStore(home: Path) = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
private val feedbackHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning")

class FeedbackList(private val home: Path, private val echo: (String) -> Unit) {
    fun run() {
        val records = prefStore(home).active(System.getProperty("user.dir"))
        if (records.isEmpty()) { echo("No feedback records."); return }
        records.forEach {
            echo("${it.id}  ${it.polarity} (${it.source}, w=${it.weight})" +
                (it.pairedWith?.let { p -> "  paired->$p" } ?: "") +
                "  ${it.reason ?: it.evidence ?: ""}")
        }
    }
}

class FeedbackDelete(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() { prefStore(home).delete(id); echo("Deleted $id") }
}

class FeedbackCommand : CliktCommand(name = "feedback", help = "Inspect and manage feedback records") {
    override fun run() = Unit
}

class FeedbackListCommand : CliktCommand(name = "list") {
    override fun run() = FeedbackList(feedbackHome) { echo(it) }.run()
}

class FeedbackDeleteCommand : CliktCommand(name = "delete") {
    private val id by argument()
    override fun run() = FeedbackDelete(feedbackHome, id) { echo(it) }.run()
}
