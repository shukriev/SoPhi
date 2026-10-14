package dev.sophi.learning

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.system1.System1Settings
import dev.sophi.ai.system1.toJson
import dev.sophi.ai.system1.withSystem1
import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.currentCoroutineContext
import java.nio.file.Path

/**
 * Wraps [s2] with the System-1 tier configured in `[home]/system1.json`, logging every tagged call
 * to `[home]/system1/decisions.jsonl` (ADR-042). No file: [s2] itself. Every host calls this, so
 * the CLI and the companion read one file and cannot diverge.
 */
fun system1FromHome(s2: LLMProvider, home: Path, onWarning: (String) -> Unit): LLMProvider {
    val log = JsonlLog(home.resolve("system1").resolve("decisions.jsonl"))
    return withSystem1(
        s2, System1Settings.load(home.resolve("system1.json"), onWarning),
        sink = { d -> log.append(d.copy(sessionId = currentCoroutineContext()[SessionIdContext]?.sessionId).toJson()) },
        onWarning = onWarning
    )
}
