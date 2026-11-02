package dev.sophi.sdk

import dev.sophi.core.dryrun.ChangePlanStore
import dev.sophi.core.dryrun.PlanFirst

/** What [RuntimeBuilder.planFirst] set: when turns record changes, whether server read-only hints count, where plans live. */
class PlanFirstSettings(val mode: PlanFirst, val trustReadOnlyHints: Boolean, val store: ChangePlanStore)
