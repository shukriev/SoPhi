package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand

class ScheduleCommand : CliktCommand(name = "schedule", help = "Manage scheduled and goal-based background tasks") {
    override fun run() = Unit
}
