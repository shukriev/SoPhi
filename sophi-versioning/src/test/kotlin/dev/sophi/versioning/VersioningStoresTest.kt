package dev.sophi.versioning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class VersioningStoresTest : FunSpec({
    test("a default home moves to the shared store and keeps its history; any other home stays embedded") {
        val home = tempdir().toPath(); val root = tempdir().toPath(); val other = tempdir().toPath()
        val old = VersionStore(home).record(ArtifactType.CONFIG, "default", "{}", ProducedBy.HUMAN)   // pre-M6, embedded
        ScorecardStore(home).record(old.id, 0.5, mapOf("a" to 0.5), emptyList(), 2)
        val shared = mapOf(home to "versioning")
        VersioningStores.versionStore(home, root, shared).use { vs ->
            VersioningStores.scorecardStore(home, root, shared).use { sc ->
                vs.history(ArtifactType.CONFIG, "default").map { it.id } shouldBe listOf(old.id)
                vs.record(ArtifactType.CONFIG, "default", "{\"x\":1}", ProducedBy.HUMAN).parentVersionId shouldBe old.id
                sc.forConfigVersion(old.id).size shouldBe 1
                vs.get("missing") shouldBe null   // a null result on the shared store is not a reason to open embedded
            }
        }
        Files.isRegularFile(home.resolve("arcadedb")) shouldBe true   // the blocker file, not a db
        Files.isDirectory(root.resolve("databases").resolve("versioning")) shouldBe true
        VersioningStores.versionStore(other, root, shared).use { it.record(ArtifactType.SKILL, "s", "x", ProducedBy.HUMAN) }
        Files.isDirectory(other.resolve("arcadedb")) shouldBe true
    }

})
