package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.skills.SkillInstaller
import java.nio.file.Path

private val globalSkillsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")

class SkillInstall(
    private val installer: SkillInstaller,
    private val targetDir: Path,
    private val source: String,
    private val only: String?,
    private val echo: (String) -> Unit
) {
    fun run() {
        val onlySet = only?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val result = installer.install(source, targetDir, onlySet)
        if (result.installed.isEmpty() && result.skipped.isEmpty() && result.notFound.isEmpty()) {
            echo("No skills found under $source.")
        } else {
            result.installed.forEach { echo("Installed: $it") }
            result.skipped.forEach { echo("Already installed: $it (skipped)") }
            result.notFound.forEach { echo("Not found: $it") }
        }
    }
}

class SkillCommand : CliktCommand(name = "skill", help = "Install skills from local paths or git repos") {
    override fun run() = Unit
}

class SkillInstallCommand : CliktCommand(name = "install") {
    private val source by argument()
    private val only by option("--only", help = "Comma-separated skill ids to install")
    private val project by option("--project", help = "Install into ./.sophi/skills instead of ~/.sophi/skills").flag()

    override fun run() {
        val targetDir = if (project) Path.of(".sophi", "skills") else globalSkillsDir
        SkillInstall(SkillInstaller(), targetDir, source, only) { echo(it) }.run()
    }
}
