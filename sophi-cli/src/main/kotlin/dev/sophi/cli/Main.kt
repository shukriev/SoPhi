package dev.sophi.cli

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = SophiCli()
    .subcommands(
        McpServeCommand(),
        MemoryCommand(),
        LessonsCommand().subcommands(LessonsListCommand(), LessonsArchiveCommand()),
        FeedbackCommand().subcommands(FeedbackListCommand(), FeedbackDeleteCommand()),
        ExportCommand(),
        ScheduleCommand().subcommands(
            ScheduleRunDueCommand(), ScheduleDaemonCommand(),
            ScheduleListCommand(), ScheduleLogCommand(),
            SchedulePauseCommand(), ScheduleResumeCommand(), ScheduleRemoveCommand()
        ),
        GoalCommand().subcommands(GoalRunCommand())
    )
    .main(args)
