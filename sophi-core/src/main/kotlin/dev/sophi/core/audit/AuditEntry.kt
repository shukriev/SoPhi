package dev.sophi.core.audit

import dev.sophi.core.tools.RiskLevel
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One tool call, as written to the audit log. `args` and `result` are already redacted and truncated.
 * `decision` is `allowed` or `denied`; `outcome` is `ok`, `error`, `denied` or `cancelled` (stopped mid-run, `result` empty).
 */
@Serializable
data class AuditEntry(
    val v: Int = 1,
    val ts: Long,
    val actor: String,
    val tenant: String? = null,
    val session: String,
    val tool: String,
    val risk: RiskLevel,
    val args: String,
    val decision: String,
    val rule: String,
    val outcome: String,
    val result: String,
    val durationMs: Long,
)

/** Where audit entries go. sophi-enterprise implements this for retention and export. Must not throw for ordinary failures. */
fun interface AuditSink {
    fun record(entry: AuditEntry)
}

const val LOCAL_ACTOR = "local"

/** Who a turn acts for. Absent = LOCAL_ACTOR, no tenant. The auth hook sets it. */
class ActorContext(val actorId: String, val tenantId: String? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActorContext>
}
