package dev.sophi.sdk

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.providers.unavailableReason
import dev.sophi.core.agent.TurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Rough prompt size: about 4 characters a token, as the agent loop estimates. */
private fun estimateTokens(r: CompletionRequest): Int =
    ((r.systemPrompt?.length ?: 0) + r.messages.sumOf { it.content.length } +
        r.tools.sumOf { it.name.length + it.description.length + it.parametersJson.length }) / 4

/**
 * What this session's model requests cost, priced by [price] (null = not billed: local, or not in the
 * catalog). [maxUsd] is the session budget; a host may change it at any time.
 */
class CostMeter(private val price: (model: String) -> ModelInfo?, @Volatile var maxUsd: Double? = null) {
    private var total = 0.0

    val totalUsd: Double @Synchronized get() = total

    /** Whether [model] is billed at all, i.e. the catalog has its price. */
    fun priced(model: String): Boolean = price(model)?.priced == true

    fun record(model: String, usage: TokenUsage) = record(price(model), usage)

    /** [usage] priced at [info]; nothing when it isn't priced. */
    @Synchronized
    fun record(info: ModelInfo?, usage: TokenUsage) {
        if (info?.priced != true) return
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
 * A model to switch to when the one before it in the chain is unavailable ([unavailableReason]).
 * [provider] null = the session's own provider (another model on the same server); [price] is what a
 * model on its own provider costs, null when it is free (a local server).
 */
data class FallbackModel(
    val model: String,
    val contextWindowTokens: Int,
    val provider: LLMProvider? = null,
    val price: ModelInfo? = null,
)

/** One turn's place in the fallback chain (0 = the session's model) and who hears of a switch; [SophiRuntime] sets one per turn. */
internal class FallbackTurn(val onSwitch: suspend (TurnEvent.ModelFallback) -> Unit) : AbstractCoroutineContextElement(FallbackTurn) {
    @Volatile var at = 0
    companion object Key : CoroutineContext.Key<FallbackTurn>
}

/**
 * Every model request of a session passes through here: it is refused once the budget is spent, sent to
 * [activeModel] when it asked for the start model (`/model`), and priced into [meter]. Requests naming
 * another model (an escalation model, a System-1 student) keep theirs. When the active model is
 * unavailable before it answered, the request goes to the next of [fallbacks] for the rest of the turn.
 */
class MeteredProvider(private val delegate: LLMProvider, val meter: CostMeter, private val startModel: String) : LLMProvider {
    @Volatile var activeModel: String = startModel

    /** Tried in order when the active model is unavailable. Empty: no fallback, an error fails the request. */
    @Volatile var fallbacks: List<FallbackModel> = emptyList()

    override val name: String get() = delegate.name

    private fun prepare(request: CompletionRequest): CompletionRequest {
        meter.overBudget()?.let { throw BudgetExceededException(it) }
        return if (request.model == startModel && activeModel != startModel) request.copy(model = activeModel) else request
    }

    /** Where chain position [at] sends [sent]; past 0 only for the session's model, which [chain] reports. */
    private fun target(at: Int, sent: CompletionRequest): Pair<LLMProvider, CompletionRequest> {
        if (at == 0) return delegate to sent
        val f = fallbacks[at - 1]
        val need = estimateTokens(sent)
        check(need <= f.contextWindowTokens) {
            "${sent.model} is unavailable and the fallback ${f.model} has a ${f.contextWindowTokens}-token window, " +
                "too small for this conversation (about $need tokens). Switch to ${f.model} so it is compacted to fit, or start ${sent.model}'s server again."
        }
        return (f.provider ?: delegate) to sent.copy(model = f.model)
    }

    private fun record(at: Int, model: String, usage: TokenUsage) {
        val f = fallbacks.getOrNull(at - 1)
        if (f?.provider != null) meter.record(f.price, usage) else meter.record(model, usage)
    }

    /** Moves [turn] to the next model after [reason]; false when the chain is used up. */
    private suspend fun next(turn: FallbackTurn, model: String, reason: String): Boolean {
        if (turn.at >= fallbacks.size) return false
        turn.at++
        turn.onSwitch(TurnEvent.ModelFallback(if (turn.at == 1) model else fallbacks[turn.at - 2].model, fallbacks[turn.at - 1].model, reason))
        return true
    }

    /** This request's turn; one outside a turn (no [FallbackTurn]) starts from the session's model each time. */
    private suspend fun turnOf(sent: CompletionRequest): FallbackTurn? =
        if (sent.model != activeModel || fallbacks.isEmpty()) null else currentCoroutineContext()[FallbackTurn] ?: FallbackTurn {}

    override suspend fun complete(request: CompletionRequest): LLMResponse {
        val sent = prepare(request)
        val turn = turnOf(sent)
        while (true) {
            val at = turn?.at ?: 0
            val (p, r) = target(at, sent)
            val response = p.complete(r)
            when (response) {
                is LLMResponse.Text -> record(at, r.model, response.usage)
                is LLMResponse.ToolUse -> record(at, r.model, response.usage)
                is LLMResponse.Error -> {
                    val reason = response.cause?.let(::unavailableReason)
                    if (turn != null && reason != null && next(turn, sent.model, reason)) continue
                }
            }
            return response
        }
    }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        val sent = prepare(request)
        val turn = turnOf(sent)
        while (true) {
            val at = turn?.at ?: 0
            val (p, r) = target(at, sent)
            var usage: TokenUsage? = null
            var started = false
            try {
                p.stream(r).collect { e ->
                    started = true
                    if (e is StreamEvent.Usage) usage = e.usage // cumulative: the last one is the request's total
                    emit(e)
                }
                return@flow
            } catch (e: Exception) {
                // Only before any output: a half-answered request can't be replayed on another model.
                val reason = if (started || e is CancellationException) null else unavailableReason(e)
                if (turn == null || reason == null || !next(turn, sent.model, reason)) throw e
            } finally {
                usage?.let { record(at, r.model, it) }
            }
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
