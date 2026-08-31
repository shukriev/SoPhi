package dev.sophi.cli

import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

// ArcadeDB (or the GraalVM polyglot engine it initializes for memory-enabled commands) spins up
// non-daemon background threads that are never explicitly torn down by any single subcommand —
// MemoryCommand.kt's palace() in particular is opened and never closed. Rather than chase down
// and .close() every call site individually, force the JVM to exit once Clikt's own dispatch
// returns normally: this is the one place every subcommand routes through. ScheduleDaemonCommand's
// `while (true)` loop never returns on its own, so this line is only reached there via its
// shutdown-hook-driven signal exit, which is already tearing the process down anyway.
fun main(args: Array<String>) {
    SophiCli()
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
            SkillCommand().subcommands(SkillInstallCommand(), SkillReviewCommand(), SkillVerifyCommand()),
            VersionsCommand().subcommands(VersionsListCommand(), VersionsShowCommand(), VersionsRevertCommand()),
            EvalsCommand().subcommands(EvalsRunCommand()),
            TournamentCommand().subcommands(TournamentRunCommand(), TournamentPromoteCommand(), TournamentStatusCommand()),
            ConfigCommand().subcommands(ConfigActivateCommand(), ConfigSeedCommand())
        )
        .main(args)
    exitProcess(0)
}
