package dev.sophi.cli

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = SophiCli()
    .subcommands(
        McpServeCommand(),
        MemoryCommand(),
        LessonsCommand().subcommands(LessonsListCommand(), LessonsArchiveCommand()),
        FeedbackCommand().subcommands(FeedbackListCommand(), FeedbackDeleteCommand()),
        ExportCommand()
    )
    .main(args)
