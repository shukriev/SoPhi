package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression: SophiCli registers subcommands (mcp-serve/lessons/feedback/export). Clikt's
 * default for a command with subcommands is invokeWithoutSubcommand = false, which makes
 * `parse()` require one and print usage instead of ever reaching `run()` — silently breaking
 * the primary `sophi --model ...` chat path with no error, only the help screen. Verified live
 * against the built jar: before this flag, `sophi --provider ... --model ...` printed usage;
 * with it, a chat session starts. run()'s own `if (currentContext.invokedSubcommand != null)
 * return` is what then keeps `sophi lessons ...` etc. from also starting a chat session.
 */
class SophiCliTest : FunSpec({
    test("invokeWithoutSubcommand is enabled so the no-subcommand chat path can still run") {
        SophiCli().invokeWithoutSubcommand shouldBe true
    }
})
