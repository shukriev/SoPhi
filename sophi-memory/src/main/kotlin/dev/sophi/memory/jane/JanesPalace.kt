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
    onWarning: (String) -> Unit = {}
) : MemoryTechnique {
    private val store = PalaceStore(config.home)
    private val profile = UserProfile(store)
    private val router = embeddingProvider?.let { RoomRouter(it) }
    private val walker = embeddingProvider?.let { PalaceWalker(store, profile, it, config) }
    private val encoder = llmProvider?.let { SignificanceEncoder(it, config, onWarning) }
    private val writer = embeddingProvider?.let {
        MemoryWriter(store, profile, it, embeddingModelName, config)
    }
    private val forgetEngine = ForgetEngine(store, profile)
    private val consolidator = Consolidator(store, forgetEngine, llmProvider, config)

    override suspend fun recall(query: RecallQuery): MemoryBlock? {
        val w = walker ?: return null
        val vector = embeddingProvider!!.embed(listOf(query.userInput)).first()
        val rooms = router!!.route(vector, config.routeTopK)
        return w.walk(query, vector, rooms)
    }

    override suspend fun observe(turn: TurnObservation) {
        val e = encoder ?: return
        val w = writer ?: return
        val recent = store.memories().values.filter { it.active }
            .sortedByDescending { it.createdAt }.take(config.recentWindow)
        val verdict = e.encode(turn, recent) ?: return
        w.write(turn, verdict)
    }

    override suspend fun consolidate(nowMs: Long): ConsolidationReport = consolidator.run(nowMs)

    fun consolidationDue(nowMs: Long): Boolean = consolidator.isDue(nowMs)

    override suspend fun forget(request: ForgetRequest): ForgetResult =
        forgetEngine.forget(request, System.currentTimeMillis())

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
            "state" to when { m.supersededBy != null -> "superseded"; m.softDeletedAt != null -> "soft-deleted"; else -> "active" }
        )
    )
}
