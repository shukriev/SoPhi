package dev.sophi.memory.jane

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.ConsolidationReport
import dev.sophi.memory.ForgetRequest
import dev.sophi.memory.ForgetResult
import dev.sophi.memory.MemoryBlock
import dev.sophi.memory.MemoryTechnique
import dev.sophi.memory.MemoryView
import dev.sophi.memory.ProfileAction
import dev.sophi.memory.ProfileAttributeView
import dev.sophi.memory.RecallQuery
import dev.sophi.memory.TurnObservation
import dev.sophi.versioning.VersionStore
import kotlinx.coroutines.runBlocking

/**
 * The memory-palace implementation of MemoryTechnique — Jane (spec §3.3).
 * Providers are nullable so the CLI command group can operate on stored data alone:
 * recall/search/observe degrade to no-ops without their provider; browse/forget/profile
 * always work.
 */
class JanesPalace(
    private val config: JanesPalaceConfig,
    llmProvider: LLMProvider?,
    private val embeddingProvider: EmbeddingProvider?,
    embeddingModelName: String = "unknown",
    onWarning: (String) -> Unit = {},
    versionStore: VersionStore? = null
) : MemoryTechnique {
    private val store = config.sharedRoot?.let { PalaceStore.shared(config.home, it) } ?: PalaceStore(config.home)
    private val profile: UserProfile
    private val walker: PalaceWalker?
    private val encoder: SignificanceEncoder?
    private val writer: MemoryWriter?
    private val forgetEngine: ForgetEngine
    private val consolidator: Consolidator

    init {
        try {   // the store holds a shared-server lease: close it if anything after it fails
            profile = UserProfile(store)
            walker = embeddingProvider?.let { PalaceWalker(store, profile, it, config) }
            encoder = llmProvider?.let { SignificanceEncoder(it, config, onWarning) }
            writer = embeddingProvider?.let { MemoryWriter(store, profile, it, embeddingModelName, config) }
            forgetEngine = ForgetEngine(store, profile)
            consolidator = Consolidator(
                store, forgetEngine, llmProvider, config,
                ConsolidationHistoryStore(config.home.resolve("consolidations.jsonl")),
                versionStore
            )
            embeddingProvider?.let { reembedIfModelChanged(it, embeddingModelName, onWarning) }
        } catch (e: Throwable) { store.close(); throw e }
    }

    /**
     * Vectors from another model can't be searched with this one (a different size breaks the index; the same size
     * still means something else), so they are dropped and every memory is embedded again. A memory left without a
     * vector, after a failed run, is embedded on the next start.
     */
    private fun reembedIfModelChanged(provider: EmbeddingProvider, model: String, onWarning: (String) -> Unit) {
        val size = store.vectorSize()
        val otherModel = model != "unknown" && store.embeddingModels().any { it.isNotBlank() && it != "unknown" && it != model }
        if ((size != null && size != provider.dimensions) || otherModel) store.dropVectors()
        val missing = store.memoriesWithoutVectors().ifEmpty { return }
        try {
            // ponytail: blocks startup while it embeds; a background pass if palaces grow to many thousands of memories
            missing.chunked(64).forEach { part ->
                val vectors = runBlocking { provider.embed(part.map { it.text }) }
                part.forEachIndexed { i, m -> store.putEmbedding(m.id, model, vectors[i]) }
            }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt()
        } catch (e: Exception) {
            onWarning("Memory couldn't finish updating to the new embedding model (${e.message}). It will try again next time SoPhi starts.")
        }
    }

    override suspend fun recall(query: RecallQuery): MemoryBlock? {
        val w = walker ?: return null
        val vector = embeddingProvider!!.embed(listOf(query.userInput)).first()
        return w.walk(query, vector)
    }

    override suspend fun observe(turn: TurnObservation) {
        val e = encoder ?: return
        val w = writer ?: return
        val recent = store.memories().values.filter { it.active }
            .sortedByDescending { it.createdAt }.take(config.recentWindow)
        val verdict = e.encode(turn, recent) ?: run { w.logEncoderNoVerdict(turn); return }
        if (turn.ambient) {
            meetingScores.addLast(turn.nowMs to (verdict.meeting ?: 0.0).coerceIn(0.0, 1.0))
            while (meetingScores.size > 200) meetingScores.pollFirst()
        }
        w.write(turn, verdict)
    }

    // Ambient meeting scores, newest last; bounded so a day of listening can't grow it.
    private val meetingScores = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, Double>>()

    /** The highest ambient meeting score recorded at or after [sinceMs]; 0.0 when none. */
    fun meetingScoreSince(sinceMs: Long): Double =
        meetingScores.filter { it.first >= sinceMs }.maxOfOrNull { it.second } ?: 0.0

    internal fun meetingScoreCount(): Int = meetingScores.size

    /** A commitment the user confirmed; null when memory has no embedding provider. */
    suspend fun rememberCommitment(text: String, sessionId: String, nowMs: Long): Memory? =
        writer?.rememberCommitment(text, sessionId, nowMs)

    override suspend fun consolidate(nowMs: Long): ConsolidationReport = consolidator.run(nowMs)

    /**
     * Attributes [success] to every memory recalled during [sessionId] (see [applyOutcome]).
     * Not part of [MemoryTechnique] -- callers decide what "success" means (mechanical turn
     * outcome, an LLM-judged verdict, or something else) and call this once they know; wiring an
     * actual caller is a separate follow-up, tracked alongside sophi-web/sophi-sdk's existing
     * MemoryPlugin gap.
     */
    fun recordOutcome(sessionId: String, success: Boolean) = applyOutcome(store, sessionId, success)

    fun consolidationDue(nowMs: Long): Boolean = consolidator.isDue(nowMs)

    /** Releases the underlying database (see [PalaceStore.close]). */
    fun close() = store.close()

    override suspend fun forget(request: ForgetRequest): ForgetResult =
        forgetEngine.forget(request, System.currentTimeMillis())

    override suspend fun restore(id: String): Boolean = forgetEngine.restore(id)

    /** Non-mutating preview of what forgetting [id] would remove and affect (spec §5). */
    fun previewForget(id: String): ForgetResult = forgetEngine.preview(id)

    /**
     * Unlike [recall], search applies no sensitivity floor: it backs user-facing
     * store inspection (`forget --about`), where the user is querying their own
     * memories directly — the privacy guard protects the model-facing path only.
     */
    override suspend fun search(query: String, k: Int): List<MemoryView> {
        val provider = embeddingProvider ?: return emptyList()
        val vector = provider.embed(listOf(query)).first()
        val all = store.memories()
        return store.nearest(vector, k).mapNotNull { scored ->
            all[scored.id]?.takeIf { it.active }?.let { view(it, System.currentTimeMillis()) }
        }
    }

    override fun browse(filter: BrowseFilter): List<MemoryView> {
        val nowMs = System.currentTimeMillis()
        return store.memories().values
            .filter { filter.includeHidden || it.active }
            .filter { filter.room == null || it.room.name.equals(filter.room, ignoreCase = true) }
            .filter { filter.provenance == null || it.provenance.name.equals(filter.provenance, ignoreCase = true) }
            .filter { filter.sourceSessionId == null || it.sourceSessionId == filter.sourceSessionId }
            .sortedByDescending { it.createdAt }
            .map { view(it, nowMs) }
    }

    override fun profileView(): List<ProfileAttributeView> =
        profile.all().values.sortedBy { it.path }.map { ProfileAttributeView(it.path, it.value, it.confidence) }

    override fun updateProfile(action: ProfileAction): Boolean = when (action) {
        is ProfileAction.Confirm -> profile.confirm(action.path)
        is ProfileAction.Correct -> profile.correct(action.path, action.value)
        is ProfileAction.Delete -> profile.delete(action.path)
    }

    override fun explainLastRecall(): String? = store.readLastRecall()

    override fun views(ids: List<String>): List<MemoryView> {
        val all = store.memories()
        val now = System.currentTimeMillis()
        return ids.mapNotNull { all[it] }.map { view(it, now) }
    }

    /**
     * Active, tagged memories eligible for a proactive rescue nudge. The sensitivity ceiling is
     * unconditional, not a caller-supplied parameter: this feed ultimately reaches an OS
     * notification banner, a more exposed surface than a chat reply, so SENSITIVE/RESTRICTED
     * patterns never reach it regardless of how the caller asks.
     */
    fun actionablePatterns(): List<Memory> =
        store.memories().values.filter { it.active && it.actionablePattern && it.sensitivity <= Sensitivity.PERSONAL }

    /**
     * Active, flagged, not-yet-expired commitments eligible for follow-up drafting. Same
     * unconditional PERSONAL-or-below sensitivity ceiling as [actionablePatterns] — this feed
     * reaches the same notification surface (see ADR-033). Commitments older than
     * [JanesPalaceConfig.commitmentExpiryMs] stop being surfaced here so an undismissed one
     * doesn't nag forever; they remain visible via [browse].
     */
    fun openCommitments(nowMs: Long = System.currentTimeMillis()): List<Memory> =
        store.memories().values.filter {
            it.active && it.isCommitment && it.sensitivity <= Sensitivity.PERSONAL &&
                it.createdAt >= nowMs - config.commitmentExpiryMs
        }

    /**
     * Active, timing-classified memories eligible for a proactively-timed nudge. Same
     * unconditional PERSONAL-or-below sensitivity ceiling as [actionablePatterns] and
     * [openCommitments] -- this feed reaches the same notification surface (see ADR-033).
     */
    fun habits(): List<Memory> =
        store.memories().values.filter { it.active && it.habitConfidence > 0.0 && it.sensitivity <= Sensitivity.PERSONAL }

    fun threads(): Map<String, List<String>> {
        val all = store.memories()
        return store.edges().groupBy { it.threadLabel }.mapValues { (_, edges) ->
            edges.sortedBy { all[it.fromId]?.createdAt ?: 0L }
                .flatMap { listOf(it.fromId, it.toId) }.distinct()
                .mapNotNull { all[it]?.text }
        }
    }

    private fun view(m: Memory, nowMs: Long): MemoryView = MemoryView(
        id = m.id, text = m.text,
        metadata = mapOf(
            "room" to m.room.name.lowercase(),
            "salience" to "%.2f".format(m.salience),
            "priority" to "%.3f".format(priority(m, nowMs, config.halfLifeMs)),
            "ageDays" to ((nowMs - m.createdAt) / 86_400_000L).toString(),
            "sensitivity" to m.sensitivity.name,
            "provenance" to m.provenance.name,
            "source" to m.sourceSessionId,
            "state" to when { m.supersededBy != null -> "superseded"; m.softDeletedAt != null -> "soft-deleted"; else -> "active" }
        )
    )
}
