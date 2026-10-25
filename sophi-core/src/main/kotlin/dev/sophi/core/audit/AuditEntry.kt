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

// ponytail: each string is redacted on its first 20k chars and a cut window loses its last ~200 chars plus the token
// run there (see redactWindowed), so a long value shows less than it could; widen the window if that ever matters.
private const val REDACT_WINDOW = 20_000
private const val UNREDACTABLE = "[unredactable]"

/**
 * The one way to build an entry: [result], and each string in [argumentsJson] (parsed whole, so secret keys always
 * go), is redacted on a window far longer than any secret pattern match, then truncated, so a cut never leaves half a secret.
 * If redaction itself throws, that field is "[unredactable]" and the entry is still written. `decision` follows from [outcome].
 */
fun auditEntry(
    actor: String, tenant: String?, session: String, tool: String, risk: RiskLevel, argumentsJson: String,
    rule: String, outcome: String, result: String, start: Long,
) = AuditEntry(
    ts = start,
    actor = actor,
    tenant = tenant,
    session = session,
    tool = tool,
    risk = risk,
    args = runCatching { redactArguments(argumentsJson, REDACT_WINDOW, budget = 4000).take(4000) }.getOrDefault(UNREDACTABLE),
    decision = if (outcome == "denied") "denied" else "allowed",
    rule = rule,
    outcome = outcome,
    result = runCatching { redactWindowed(result, REDACT_WINDOW).take(500) }.getOrDefault(UNREDACTABLE),
    durationMs = System.currentTimeMillis() - start,
)

/** Builds and records an entry; anything thrown on the way, Errors included (a regex overflow), drops only this entry. */
fun AuditSink.recordQuietly(entry: () -> AuditEntry) {
    runCatching { record(entry()) }
}
