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
            SchedulePauseCommand(), ScheduleResumeCommand(), ScheduleRemoveCommand(),
            ScheduleInstallLaunchdCommand()
        ),
        ProposalsCommand().subcommands(ProposalsListCommand(), ProposalsAcceptCommand(), ProposalsRejectCommand()),
        GoalCommand().subcommands(GoalRunCommand()),
        SkillCommand().subcommands(SkillInstallCommand(), SkillReviewCommand()),
        VersionsCommand().subcommands(VersionsListCommand(), VersionsShowCommand(), VersionsRevertCommand()),
        EvalsCommand().subcommands(EvalsRunCommand()),
        TournamentCommand().subcommands(TournamentRunCommand(), TournamentPromoteCommand(), TournamentStatusCommand()),
        ConfigCommand().subcommands(ConfigActivateCommand())
    )
    .main(args)
