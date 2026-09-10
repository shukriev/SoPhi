package dev.sophi.memory.jane

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class JanesPalaceTest : FunSpec({
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
})
