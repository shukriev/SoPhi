package dev.sophi.versioning

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.io.path.createTempDirectory

class VersionStoreTest : FunSpec({
    test("record() creates a root version with no parent") {
        val store = VersionStore(createTempDirectory("versioning-test"))

        val v1 = store.record(ArtifactType.SKILL, "greet", "hello world", ProducedBy.HUMAN)

        v1.parentVersionId shouldBe null
        v1.content shouldBe "hello world"
        v1.artifactType shouldBe ArtifactType.SKILL
        v1.artifactId shouldBe "greet"
    }

    test("a second record() for the same artifact chains to the first as parent") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val v1 = store.record(ArtifactType.SKILL, "greet", "v1 content", ProducedBy.HUMAN)

        val v2 = store.record(ArtifactType.SKILL, "greet", "v2 content", ProducedBy.WRITE_SKILL_TOOL)

        v2.parentVersionId shouldBe v1.id
    }

    test("history() returns every version for an artifact, most recent last") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val v1 = store.record(ArtifactType.SKILL, "greet", "v1", ProducedBy.HUMAN)
        val v2 = store.record(ArtifactType.SKILL, "greet", "v2", ProducedBy.HUMAN)

        val history = store.history(ArtifactType.SKILL, "greet")

        history.map { it.id } shouldBe listOf(v1.id, v2.id)
    }

    test("activeVersion() returns the newest HUMAN version, ignoring a newer TOURNAMENT-produced one") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val seed = store.record(ArtifactType.CONFIG, "default", "seed content", ProducedBy.HUMAN)
        store.record(ArtifactType.CONFIG, "default", "rejected challenger content", ProducedBy.TOURNAMENT)

        store.activeVersion(ArtifactType.CONFIG, "default")?.id shouldBe seed.id
    }

    test("activeVersion() picks up a later HUMAN promotion over an even-later TOURNAMENT proposal") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        store.record(ArtifactType.CONFIG, "default", "seed content", ProducedBy.HUMAN)
        val promoted = store.record(ArtifactType.CONFIG, "default", "promoted challenger content", ProducedBy.HUMAN)
        store.record(ArtifactType.CONFIG, "default", "a later, unrelated rejected proposal", ProducedBy.TOURNAMENT)

        store.activeVersion(ArtifactType.CONFIG, "default")?.id shouldBe promoted.id
    }

    test("activeVersion() returns null when no HUMAN version exists yet") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        store.record(ArtifactType.CONFIG, "default", "proposal only", ProducedBy.TOURNAMENT)

        store.activeVersion(ArtifactType.CONFIG, "default") shouldBe null
    }

    test("history() is scoped to artifactType and artifactId — no cross-contamination") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        store.record(ArtifactType.SKILL, "greet", "skill content", ProducedBy.HUMAN)
        store.record(ArtifactType.LESSON, "greet", "lesson content", ProducedBy.REFLECTION)

        store.history(ArtifactType.SKILL, "greet") shouldHaveSize 1
        store.history(ArtifactType.LESSON, "greet") shouldHaveSize 1
    }

    test("allForType() returns every version of a given artifactType, across all artifactIds") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val a = store.record(ArtifactType.SKILL, "greet", "one", ProducedBy.HUMAN)
        val b = store.record(ArtifactType.SKILL, "farewell", "two", ProducedBy.HUMAN)
        store.record(ArtifactType.LESSON, "greet", "not a skill", ProducedBy.REFLECTION)

        store.allForType(ArtifactType.SKILL).map { it.id }.toSet() shouldBe setOf(a.id, b.id)
    }

    test("get() retrieves a specific version by id") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val v1 = store.record(ArtifactType.CONFIG, "default", "config-v1", ProducedBy.HUMAN)

        store.get(v1.id) shouldBe v1
    }

    test("get() returns null for an unknown id") {
        val store = VersionStore(createTempDirectory("versioning-test"))

        store.get("does-not-exist") shouldBe null
    }

    test("revert() writes a new version with the target's content, chained after the current head") {
        val store = VersionStore(createTempDirectory("versioning-test"))
        val v1 = store.record(ArtifactType.SKILL, "greet", "original", ProducedBy.HUMAN)
        val v2 = store.record(ArtifactType.SKILL, "greet", "broken", ProducedBy.WRITE_SKILL_TOOL)

        val reverted = store.revert(ArtifactType.SKILL, "greet", v1.id)

        reverted.content shouldBe "original"
        reverted.parentVersionId shouldBe v2.id
        reverted.producedBy shouldBe ProducedBy.HUMAN
        store.history(ArtifactType.SKILL, "greet") shouldHaveSize 3
    }

    test("two independent VersionStore instances over the same home dir see each other's writes") {
        // Regression test for the open-write-close requirement: open a store, record once, then
        // open a SECOND independent VersionStore instance pointed at the same home directory and
        // confirm it can also read — proves the first instance released its lock between
        // operations rather than holding the database open.
        val home = createTempDirectory("versioning-test")
        val storeA = VersionStore(home)
        storeA.record(ArtifactType.SKILL, "greet", "v1", ProducedBy.HUMAN)

        val storeB = VersionStore(home)
        val fromB = storeB.history(ArtifactType.SKILL, "greet")

        fromB shouldHaveSize 1
    }
})
