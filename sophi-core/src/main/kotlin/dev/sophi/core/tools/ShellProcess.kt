package dev.sophi.core.tools

/**
 * `sh -c [command]` with dot-decimal numbers: under a comma-decimal locale awk prints 12,50 and the
 * printf builtin rejects 12.5, which breaks the arithmetic a model writes. Only LC_NUMERIC changes;
 * text encoding and language stay the user's. [inherited] edits the inherited environment first (tests).
 */
fun shellProcess(command: String, inherited: MutableMap<String, String>.() -> Unit = {}): ProcessBuilder =
    ProcessBuilder("sh", "-c", command).apply {
        val env = environment().apply(inherited)
        // LC_ALL overrides every LC_*, so fold it into LANG (it was masking the other LC_* anyway).
        env.remove("LC_ALL")?.takeIf { it.isNotEmpty() }?.let { all ->
            env.keys.removeAll { it.startsWith("LC_") }
            env["LANG"] = all
        }
        env["LC_NUMERIC"] = "C"
    }
