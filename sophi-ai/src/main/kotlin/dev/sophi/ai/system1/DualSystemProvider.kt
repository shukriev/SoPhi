package dev.sophi.ai.system1

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.providers.buildOpenAiCompatProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random

/**
 * Routes tagged judgment calls between System 2 ([s2], the configured provider) and System 1 ([s1],
 * a small fast model) by [System1Settings.modeOf] (ADR-042). Untagged calls and every [stream] go to
 * [s2] untouched. Invariant: S1 never makes a judgment worse than today — the worst case is always
 * "S2 answered, as now".
 */
class DualSystemProvider(
    private val s2: LLMProvider,
    private val s1: LLMProvider,
    private val settings: System1Settings,
    private val sink: suspend (Decision) -> Unit,
    private val onWarning: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default
) : LLMProvider {
    override val name: String get() = s2.name

    // ponytail: one shadow in flight — S1 and S2 likely share one GPU; raise it if S1 gets its own hardware.
    private val shadowPermit = Semaphore(1)
    private val sinkWarned = AtomicBoolean(false)

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = s2.stream(request)

    override suspend fun complete(request: CompletionRequest): LLMResponse {
        val judgment = request.judgment ?: return s2.complete(request)
        val mode = settings.modeOf(judgment)
        return when (mode) {
            System1Mode.SYSTEM2 -> {
                val c2 = call(s2, request)
                record(judgment, mode, "s2", request, c2.reply, null)
                c2.result.getOrThrow()
            }
            System1Mode.SYSTEM1 -> {
                val c1 = call(s1, s1Request(request))
                if (c1.answered) {
                    record(judgment, mode, "s1", request, null, c1.reply)
                    c1.result.getOrThrow()
                } else {
                    val c2 = call(s2, request)
                    record(judgment, mode, "s2", request, c2.reply, c1.reply)
                    c2.result.getOrThrow()
                }
            }
            System1Mode.SHADOW -> {
                val c2 = call(s2, request)
                // After S2, not alongside it: on a shared GPU a concurrent S1 call would slow the
                // user-visible one or force the server to swap models.
                if (random.nextDouble() < settings.sampleRateOf(judgment) && shadowPermit.tryAcquire()) {
                    // Outlives the caller (no Job), runs on our dispatcher (no interceptor), but keeps
                    // context elements such as the session id for the sink.
                    val callerContext = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor)
                    scope.launch(callerContext) {
                        try {
                            val c1 = call(s1, s1Request(request))
                            record(judgment, mode, "s2", request, c2.reply, c1.reply)
                        } finally {
                            shadowPermit.release()
                        }
                    }
                } else {
                    record(judgment, mode, "s2", request, c2.reply, null)
                }
                c2.result.getOrThrow()
            }
        }
    }

    private fun s1Request(request: CompletionRequest) =
        request.copy(model = settings.model, reasoningEffort = "none")

    private class Call(val result: Result<LLMResponse>, val reply: DecisionReply) {
        val answered: Boolean get() = (result.getOrNull() as? LLMResponse.Text)?.content?.isNotBlank() == true
    }

    private suspend fun call(provider: LLMProvider, request: CompletionRequest): Call {
        val start = System.nanoTime()
        val result = try {
            Result.success(provider.complete(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val response = result.getOrNull()
        val usage = when (response) {
            is LLMResponse.Text -> response.usage
            is LLMResponse.ToolUse -> response.usage
            else -> null
        }
        val error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName }
            ?: (response as? LLMResponse.Error)?.message
            ?: if (response is LLMResponse.Text && response.content.isBlank()) "empty reply" else null
        return Call(result, DecisionReply(
            model = request.model,
            text = (response as? LLMResponse.Text)?.content,
            latencyMs = (System.nanoTime() - start) / 1_000_000,
            usage = usage?.let { DecisionUsage(it.inputTokens, it.outputTokens) },
            error = error
        ))
    }

    private suspend fun record(
        judgment: String, mode: System1Mode, served: String,
        request: CompletionRequest, s2Reply: DecisionReply?, s1Reply: DecisionReply?
    ) {
        val decision = Decision(
            id = UUID.randomUUID().toString(), ts = Instant.now().toString(),
            judgment = judgment, mode = mode, served = served,
            request = DecisionRequest(
                request.systemPrompt,
                request.messages.map { DecisionMessage(it.role.name, it.content) },
                request.maxTokens, request.temperature
            ),
            s2 = s2Reply, s1 = s1Reply
        )
        try {
            sink(decision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (sinkWarned.compareAndSet(false, true)) {
                onWarning("system1: decision log write failed (${e.message}); further failures are silent")
            }
        }
    }
}

/** [s2] itself when [settings] is null (System 1 off); otherwise [s2] wrapped with an OpenAI-compatible S1 client. */
fun withSystem1(
    s2: LLMProvider,
    settings: System1Settings?,
    sink: suspend (Decision) -> Unit,
    onWarning: (String) -> Unit
): LLMProvider = if (settings == null) s2 else DualSystemProvider(
    s2,
    buildOpenAiCompatProvider(
        settings.baseUrl, settings.apiKey, settings.model, name = "system1",
        requestTimeout = Duration.ofSeconds(settings.timeoutSeconds), maxRetries = settings.maxRetries
    ),
    settings, sink, onWarning
)
