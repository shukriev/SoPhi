package dev.sophi.sdk

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * What this session's model requests cost, priced by [price] (null = not billed: local, or not in the
 * catalog). [maxUsd] is the session budget; a host may change it at any time.
 */
class CostMeter(private val price: (model: String) -> ModelInfo?, @Volatile var maxUsd: Double? = null) {
    private var total = 0.0

    val totalUsd: Double @Synchronized get() = total

    /** Whether [model] is billed at all, i.e. the catalog has its price. */
    fun priced(model: String): Boolean = price(model)?.priced == true

    @Synchronized
    fun record(model: String, usage: TokenUsage) {
        val info = price(model)?.takeIf { it.priced } ?: return
        total += cost(info, usage)
    }

    /** The stop message once the budget is spent, else null. A session that has cost nothing is never over, even with a $0 budget. */
    fun overBudget(): String? {
        val max = maxUsd ?: return null
        val spent = totalUsd
        return if (spent > 0 && spent >= max)
            String.format(Locale.ROOT, "[Stopped: this session has cost $%.2f, at or over its $%.2f budget]", spent, max)
        else null
    }

    companion object {
        /**
         * USD for one request: input includes cache reads, which are billed at the cache price when there is one.
         * ponytail: cache writes are billed as plain input (~25% under on Anthropic writes); add cache_write when exact bills matter.
         */
        fun cost(info: ModelInfo, usage: TokenUsage): Double {
            val input = info.inputUsdPerM ?: return 0.0
            val output = info.outputUsdPerM ?: return 0.0
            val cached = usage.cacheReadTokens.coerceIn(0, usage.inputTokens)
            return ((usage.inputTokens - cached) * input + cached * (info.cacheReadUsdPerM ?: input) + usage.outputTokens * output) / 1e6
        }
    }
}

/**
 * Every model request of a session passes through here: it is refused once the budget is spent, sent to
 * [activeModel] when it asked for the start model (`/model`), and priced into [meter]. Requests naming
 * another model (an escalation model, a System-1 student) keep theirs.
 */
class MeteredProvider(private val delegate: LLMProvider, val meter: CostMeter, private val startModel: String) : LLMProvider {
    @Volatile var activeModel: String = startModel

    override val name: String get() = delegate.name

    private fun prepare(request: CompletionRequest): CompletionRequest {
        meter.overBudget()?.let { throw BudgetExceededException(it) }
        return if (request.model == startModel && activeModel != startModel) request.copy(model = activeModel) else request
    }

    override suspend fun complete(request: CompletionRequest): LLMResponse {
        val sent = prepare(request)
        return delegate.complete(sent).also { r ->
            when (r) {
                is LLMResponse.Text -> meter.record(sent.model, r.usage)
                is LLMResponse.ToolUse -> meter.record(sent.model, r.usage)
                is LLMResponse.Error -> Unit
            }
        }
    }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        val sent = prepare(request)
        var usage: TokenUsage? = null
        try {
            delegate.stream(sent).collect { e ->
                if (e is StreamEvent.Usage) usage = e.usage // cumulative: the last one is the request's total
                emit(e)
            }
        } finally {
            usage?.let { meter.record(sent.model, it) }
        }
    }

    companion object {
        /** [provider] metered against [catalog]; [priced] false (a local server) means nothing is ever billed. */
        fun wrap(provider: LLMProvider, model: String, catalog: ModelCatalog?, priced: Boolean, catalogProvider: String?): MeteredProvider =
            provider as? MeteredProvider ?: MeteredProvider(
                provider,
                CostMeter({ m -> if (priced) catalog?.find(m, catalogProvider) else null }),
                model,
            )
    }
}
