package dev.sophi.core.agent

/**
 * Escalates to a human when a tool-round loop shows signs of flailing (repeated failures,
 * a search broadening beyond where the task pointed, or nearing the round budget) instead of
 * silently burning through the full round count. Mirrors ConfirmationPolicy's shape: interactive
 * callers implement a real y/n prompt, unattended callers pick a fixed answer — there is no one
 * to ask in a scheduled run, so NEVER_CONTINUE stops at the first trigger rather than hanging.
 */
fun interface LoopGuardPolicy {
    suspend fun askToContinue(reason: String): Boolean

    companion object {
        val ALWAYS_CONTINUE: LoopGuardPolicy = LoopGuardPolicy { true }
        val NEVER_CONTINUE: LoopGuardPolicy = LoopGuardPolicy { false }
    }
}
