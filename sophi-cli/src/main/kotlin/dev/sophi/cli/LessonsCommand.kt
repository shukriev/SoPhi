package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.LessonStore
import java.nio.file.Path

private fun store(home: Path) = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
private val defaultHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning")

class LessonsList(private val home: Path, private val echo: (String) -> Unit) {
    fun run(all: Boolean = false) {
        val scope = System.getProperty("user.dir")
        val lessons = if (all) store(home).activeIncludingGlobal(scope) else store(home).active(scope)
        if (lessons.isEmpty()) { echo("No lessons."); return }
        lessons.forEach { echo("${it.id}  [${it.kind}] use=${it.useCount}  ${it.text}") }
    }
}

class LessonsArchive(private val home: Path, private val id: String, private val echo: (String) -> Unit) {
    fun run() { store(home).archive(id); echo("Archived $id") }
}

class LessonsCommand : CliktCommand(name = "lessons", help = "Inspect and manage learned lessons") {
    override fun run() = Unit
}

class LessonsListCommand : CliktCommand(name = "list") {
    private val all by option("--all", help = "Include global lessons").flag()
    override fun run() = LessonsList(defaultHome) { echo(it) }.run(all)
}

class LessonsArchiveCommand : CliktCommand(name = "archive") {
    private val id by argument()
    override fun run() = LessonsArchive(defaultHome, id) { echo(it) }.run()
}
