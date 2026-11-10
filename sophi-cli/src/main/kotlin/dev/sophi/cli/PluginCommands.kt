package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sophi.sdk.InstalledPlugins

class PluginInstallCommand : CliktCommand(
    name = "install",
    help = "Install a plugin from git: git:<url>[#ref]. The repo must ship a prebuilt plugin.jar at its root; " +
        "the exact commit is pinned in ~/.sophi/plugins/plugins.lock.json and shown before you confirm."
) {
    private val source by argument()
    private val update by option("--update", help = "Re-resolve the ref and move an installed plugin's pin").flag()

    override fun run() {
        val pin = try {
            InstalledPlugins.install(source, update = update) { preview ->
                echo(preview.describe())
                print("Install ${preview.name}? [y/N] ")
                readlnOrNull()?.trim()?.lowercase() == "y"
            }
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message)
        } catch (e: IllegalStateException) {
            throw CliktError(e.message)
        }
        echo(if (pin == null) "Not installed." else "Installed at ${pin.commit}. It loads the next time SoPhi starts.")
    }
}

class PluginsCommand : CliktCommand(name = "plugins", help = "List or remove plugins installed from git") {
    override fun run() = Unit
}

class PluginsListCommand : CliktCommand(name = "list", help = "List installed plugins and their pinned commits") {
    override fun run() {
        val pins = InstalledPlugins.list()
        if (pins.isEmpty()) echo("No plugins installed.")
        pins.forEach { (name, pin) -> echo("$name  ${pin.commit}  ${pin.source}") }
    }
}

class PluginsRemoveCommand : CliktCommand(name = "remove", help = "Remove an installed plugin") {
    private val name by argument()
    override fun run() = echo(if (InstalledPlugins.remove(name = name)) "Removed $name." else "No plugin named $name.")
}
