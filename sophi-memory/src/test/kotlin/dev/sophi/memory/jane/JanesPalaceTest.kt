package dev.sophi.memory.jane

import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.RecallQuery
import dev.sophi.memory.TurnObservation
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class JanesPalaceTest : FunSpec({
    // Regression (playbook assistant-6): top-3 descriptor routing skipped ENTITIES for "write down what
    // you know about...", so stored facts there were never recalled. Recall must reach every room.
    test("recall finds a matching memory in every room") {
        val home = tempdir().toPath()
        val embeddings = FakeEmbeddingProvider()
        val texts = mapOf(
            Room.ENTITIES to "Tomas Berg owns the payments API", Room.TASKS to "renew the parking permit",
            Room.EPISODES to "the offsite moved to Lisbon", Room.KNOWLEDGE to "feature flags start with ff_",
            Room.NARRATIVE to "the outage led to the on-call rotation")
        PalaceStore(home).let { store ->
            MemoryWriter(store, UserProfile(store), embeddings, "fake", JanesPalaceConfig()).write(
                TurnObservation("s1", "u", "a", 1_000L),
                EncoderVerdict(texts.map { (room, text) -> VerdictMemory(text = text, room = room.name, dur = 1.0) }))
            store.close()
        }
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, embeddings, "fake")
        texts.values.forEach { text -> palace.recall(RecallQuery("s2", text, 2_000L))!!.rendered shouldContain text }
        palace.close()
    }

    // A palace embedded by one model can't be searched with another: its index has the old size, and
    // even at the same size the old vectors mean something else. Startup re-embeds them with the current model.
    fun seedPalace(home: java.nio.file.Path, embeddings: dev.sophi.ai.api.EmbeddingProvider, model: String, text: String) =
        PalaceStore(home).let { store ->
            runBlocking { MemoryWriter(store, UserProfile(store), embeddings, model, JanesPalaceConfig()).write(
                TurnObservation("s1", "u", "a", 1_000L),
                EncoderVerdict(listOf(VerdictMemory(text = text, room = Room.KNOWLEDGE.name, dur = 1.0)))) }
            store.close()
        }

    test("a palace embedded at another size is re-embedded with the current model at startup") {
        val home = tempdir().toPath()
        seedPalace(home, FakeEmbeddingProvider(64), "openai", "feature flags start with ff_")
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, FakeEmbeddingProvider(32), "nomic")
        palace.recall(RecallQuery("s2", "feature flags start with ff_", 2_000L))!!.rendered shouldContain "feature flags"
        palace.close()
        PalaceStore(home).let { it.embeddings().values.map { v -> v.size }.toSet() shouldBe setOf(32); it.close() }
    }

    test("a palace embedded by another model of the same size is re-embedded at startup") {
        val home = tempdir().toPath()
        seedPalace(home, FakeEmbeddingProvider(64), "other", "renew the parking permit")
        JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, FakeEmbeddingProvider(64), "nomic").close()
        PalaceStore(home).let { it.embeddingModel() shouldBe "nomic"; it.close() }
    }

    test("a re-embed that fails warns and still opens the palace") {
        val home = tempdir().toPath()
        seedPalace(home, FakeEmbeddingProvider(64), "openai", "the offsite moved to Lisbon")
        val down = object : dev.sophi.ai.api.EmbeddingProvider {
            override val dimensions = 32
            override suspend fun embed(texts: List<String>): List<FloatArray> = error("embedder down")
        }
        val warnings = mutableListOf<String>()
        JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, down, "nomic", onWarning = { warnings += it }).close()
        warnings shouldHaveSize 1
        // The next start, with the embedder back, finishes the job.
        JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, FakeEmbeddingProvider(32), "nomic").close()
        PalaceStore(home).let { it.embeddings().values.map { v -> v.size }.toSet() shouldBe setOf(32); it.close() }
    }

    test("consolidate() records a MEMORY_CONSOLIDATION Version when JanesPalace is given a VersionStore") {
        val vs = VersionStore(tempdir().toPath())
        val palace = JanesPalace(
            JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "test-model"),
            llmProvider = null, embeddingProvider = null,
            versionStore = vs
        )

        runBlocking { palace.consolidate(1_000L) }

        vs.allForType(ArtifactType.MEMORY_CONSOLIDATION) shouldHaveSize 1
        palace.close()
    }

    test("actionablePatterns returns only active, tagged memories at or below PERSONAL sensitivity") {
        // ArcadeDB locks its database directory to one open instance at a time (see PalaceStore's
        // class doc), so the seeding store must close before JanesPalace opens the same home.
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        val base = Memory(
            "x", "text", Room.EPISODES, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
            Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s", actionablePattern = true
        )
        seed.upsertMemory(base.copy(id = "mem_ok", sensitivity = Sensitivity.PERSONAL))
        seed.upsertMemory(base.copy(id = "mem_public", sensitivity = Sensitivity.PUBLIC))
        seed.upsertMemory(base.copy(id = "mem_sensitive", sensitivity = Sensitivity.SENSITIVE))
        seed.upsertMemory(base.copy(id = "mem_restricted", sensitivity = Sensitivity.RESTRICTED))
        seed.upsertMemory(base.copy(id = "mem_untagged", actionablePattern = false))
        seed.upsertMemory(base.copy(id = "mem_deleted", softDeletedAt = 1L))
        seed.close()

        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "test-model"), llmProvider = null, embeddingProvider = null)
        val ids = palace.actionablePatterns().map { it.id }.toSet()
        ids shouldBe setOf("mem_ok", "mem_public")
        palace.close()
    }

    test("openCommitments returns only active, flagged, unexpired commitments at or below PERSONAL sensitivity") {
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        val dayMs = 24 * 3_600_000L
        val nowMs = 1_000_000_000L
        val base = Memory(
            "x", "text", Room.TASKS, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
            Sensitivity.PERSONAL, Provenance.USER_DIRECT, nowMs, nowMs, "s", isCommitment = true
        )
        seed.upsertMemory(base.copy(id = "mem_ok", sensitivity = Sensitivity.PERSONAL))
        seed.upsertMemory(base.copy(id = "mem_public", sensitivity = Sensitivity.PUBLIC))
        seed.upsertMemory(base.copy(id = "mem_sensitive", sensitivity = Sensitivity.SENSITIVE))
        seed.upsertMemory(base.copy(id = "mem_restricted", sensitivity = Sensitivity.RESTRICTED))
        seed.upsertMemory(base.copy(id = "mem_untagged", isCommitment = false))
        seed.upsertMemory(base.copy(id = "mem_deleted", softDeletedAt = 1L))
        seed.upsertMemory(base.copy(id = "mem_expired", createdAt = nowMs - 31 * dayMs))
        seed.close()

        val palace = JanesPalace(
            JanesPalaceConfig(home = home, sessionModel = "test-model", commitmentExpiryMs = 30 * dayMs),
            llmProvider = null, embeddingProvider = null
        )
        val ids = palace.openCommitments(nowMs).map { it.id }.toSet()
        ids shouldBe setOf("mem_ok", "mem_public")
        palace.close()
    }

    test("browse filters by sourceSessionId, ANDs with room, and view exposes it as metadata 'source'") {
        // ArcadeDB locks its database directory to one open instance at a time (see PalaceStore's
        // class doc), so the seeding store must close before JanesPalace opens the same home.
        val home = tempdir().toPath()
        val seed = PalaceStore(home)
        val base = Memory(
            "x", "text", Room.EPISODES, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
            Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s"
        )
        seed.upsertMemory(base.copy(id = "mem_ambient", sourceSessionId = "ambient"))
        seed.upsertMemory(base.copy(id = "mem_chat", sourceSessionId = "sess_123"))
        seed.close()

        val palace = JanesPalace(
            JanesPalaceConfig(home = home, sessionModel = "test-model"),
            llmProvider = null, embeddingProvider = null
        )

        palace.browse(BrowseFilter(sourceSessionId = "ambient")).map { it.id } shouldBe listOf("mem_ambient")
        palace.browse(BrowseFilter()).map { it.id }.toSet() shouldBe setOf("mem_ambient", "mem_chat")
        // Exact match, not case-insensitive like room/provenance: these are opaque ids.
        palace.browse(BrowseFilter(sourceSessionId = "AMBIENT")) shouldBe emptyList()
        // ANDs with the other clauses rather than replacing them.
        palace.browse(BrowseFilter(room = "knowledge", sourceSessionId = "ambient")) shouldBe emptyList()
        palace.browse(BrowseFilter(room = "episodes", sourceSessionId = "ambient")).map { it.id } shouldBe
            listOf("mem_ambient")

        palace.browse(BrowseFilter(sourceSessionId = "ambient")).single().metadata["source"] shouldBe "ambient"
        palace.browse(BrowseFilter(sourceSessionId = "sess_123")).single().metadata["source"] shouldBe "sess_123"
        palace.close()
    }

    test("meetingScoreSince returns the highest ambient score since a time and ignores chat turns") {
        val llm = io.mockk.mockk<dev.sophi.ai.api.LLMProvider>()
        val scores = ArrayDeque(listOf("0.2", "0.9", "0.7"))
        io.mockk.coEvery { llm.complete(any()) } answers {
            dev.sophi.ai.api.LLMResponse.Text("""{"meeting":${scores.removeFirst()},"memories":[]}""", dev.sophi.ai.api.TokenUsage(1, 1))
        }
        val palace = JanesPalace(JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "m"), llm, FakeEmbeddingProvider(), "fake")
        runBlocking {
            palace.observe(TurnObservation("ambient", "a", "", 1_000L, ambient = true))
            palace.observe(TurnObservation("ambient", "b", "", 2_000L, ambient = true))
            palace.observe(TurnObservation("s1", "chat", "ok", 3_000L))   // chat: never counted
        }
        palace.meetingScoreSince(1_500L) shouldBe 0.9
        palace.meetingScoreSince(0L) shouldBe 0.9
        palace.meetingScoreSince(5_000L) shouldBe 0.0
        palace.close()
    }

    test("meetingScoreSince keeps at most 200 scores") {
        val llm = io.mockk.mockk<dev.sophi.ai.api.LLMProvider>()
        io.mockk.coEvery { llm.complete(any()) } returns
            dev.sophi.ai.api.LLMResponse.Text("""{"meeting":0.5,"memories":[]}""", dev.sophi.ai.api.TokenUsage(1, 1))
        val palace = JanesPalace(JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "m"), llm, FakeEmbeddingProvider(), "fake")
        runBlocking { repeat(250) { palace.observe(TurnObservation("ambient", "x$it", "", it.toLong(), ambient = true)) } }
        palace.meetingScoreSince(0L) shouldBe 0.5
        palace.meetingScoreCount() shouldBe 200
        palace.close()
    }

    test("rememberCommitment stores a tracked commitment that openCommitments returns") {
        val palace = JanesPalace(JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "m"), null, FakeEmbeddingProvider(), "fake")
        val m = runBlocking { palace.rememberCommitment("Send Ivan the FTP account list", "meeting-1", 1_000L) }!!
        m.isCommitment shouldBe true
        m.room shouldBe Room.TASKS
        m.provenance shouldBe Provenance.USER_DIRECT
        palace.openCommitments(2_000L).map { it.id } shouldBe listOf(m.id)
        palace.close()
    }

    test("rememberCommitment twice for the same item keeps one memory") {
        val palace = JanesPalace(JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "m"), null, FakeEmbeddingProvider(), "fake")
        val a = runBlocking { palace.rememberCommitment("Send Ivan the FTP account list", "meeting-1", 1_000L) }!!
        val b = runBlocking { palace.rememberCommitment("Send Ivan the FTP account list", "meeting-1", 2_000L) }!!
        b.id shouldBe a.id
        palace.openCommitments(3_000L) shouldHaveSize 1
        palace.close()
    }

    test("an old similar memory is not reused: it would fall outside the open-commitment window") {
        val cfg = JanesPalaceConfig(home = tempdir().toPath(), sessionModel = "m")
        val palace = JanesPalace(cfg, null, FakeEmbeddingProvider(), "fake")
        val old = runBlocking { palace.rememberCommitment("Send Ivan the FTP account list", "meeting-1", 0L) }!!
        val now = cfg.commitmentExpiryMs + 10_000L
        val fresh = runBlocking { palace.rememberCommitment("Send Ivan the FTP account list", "meeting-2", now) }!!
        (fresh.id == old.id) shouldBe false
        palace.openCommitments(now).map { it.id } shouldBe listOf(fresh.id)
        palace.close()
    }

    // A model that answers nothing usable (e.g. it spent its budget thinking) was only a console
    // warning; with telemetry on it is now visible in encoder.jsonl.
    test("an encoder that returns nothing usable leaves an encoder_no_verdict telemetry line") {
        val llm = io.mockk.mockk<dev.sophi.ai.api.LLMProvider>()
        io.mockk.coEvery { llm.complete(any()) } returns dev.sophi.ai.api.LLMResponse.Text("", dev.sophi.ai.api.TokenUsage(1, 1))
        val home = tempdir().toPath()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m", encoderTelemetry = true), llm, FakeEmbeddingProvider(), "fake")
        runBlocking { palace.observe(TurnObservation("ambient", "Ivan owns the FTP migration", "", 1_000L, ambient = true)) }
        palace.close()
        val log = home.resolve("encoder.jsonl").toFile()
        (log.exists() && log.readText().contains("\"outcome\":\"encoder_no_verdict\"")) shouldBe true
    }
})
