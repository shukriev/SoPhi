package dev.sophi.memory.jane

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
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
})
