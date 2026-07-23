package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import java.nio.file.Files
import java.nio.file.Path

object LaunchdPlist {
    fun build(sophiBin: String, intervalSeconds: Long): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>dev.sophi.schedule</string>
            <key>ProgramArguments</key>
            <array>
                <string>$sophiBin</string>
                <string>schedule</string>
                <string>run-due</string>
            </array>
            <key>StartInterval</key>
            <integer>$intervalSeconds</integer>
            <key>RunAtLoad</key>
            <true/>
        </dict>
        </plist>
    """.trimIndent()
}

class ScheduleInstallLaunchdCommand : CliktCommand(
    name = "install-launchd",
    help = "Write and load a launchd agent that runs 'sophi schedule run-due' on an interval (macOS only)"
) {
    private val sophiBin: String by option("--sophi-bin", help = "Path to the sophi executable").default("sophi")
    private val intervalSeconds: Long by option("--interval-seconds").long().default(60)

    override fun run() {
        val plistPath = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.sophi.schedule.plist")
        Files.createDirectories(plistPath.parent)
        Files.writeString(plistPath, LaunchdPlist.build(sophiBin, intervalSeconds))
        val exit = ProcessBuilder("launchctl", "load", "-w", plistPath.toString())
            .inheritIO().start().waitFor()
        if (exit == 0) echo("Installed and loaded $plistPath")
        else echo("Wrote $plistPath but 'launchctl load' exited with code $exit")
    }
}
