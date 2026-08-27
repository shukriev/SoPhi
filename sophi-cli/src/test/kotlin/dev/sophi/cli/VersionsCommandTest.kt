package dev.sophi.cli

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

class VersionsCommandTest : FunSpec({
    test("list reports 'no versions' for an artifact with no recorded history") {
        val home = createTempDirectory("versions-cli-test")
        val lines = mutableListOf<String>()

        VersionsList(VersionStore(home), ArtifactType.SKILL, "greet") { lines.add(it) }.run()

        lines shouldHaveSize 1
        lines.first() shouldContain "No versions"
    }

    test("list prints every version newest first, with its producedBy") {
        val home = createTempDirectory("versions-cli-test")
        val store = VersionStore(home)
        val v1 = store.record(ArtifactType.SKILL, "greet", "v1", ProducedBy.HUMAN)
        val v2 = store.record(ArtifactType.SKILL, "greet", "v2", ProducedBy.WRITE_SKILL_TOOL)
        val lines = mutableListOf<String>()

        VersionsList(store, ArtifactType.SKILL, "greet") { lines.add(it) }.run()

        lines shouldHaveSize 2
        lines[0] shouldContain v2.id
        lines[0] shouldContain "WRITE_SKILL_TOOL"
        lines[1] shouldContain v1.id
    }

    test("revert restores the target version's content as a new version and reports success") {
        val home = createTempDirectory("versions-cli-test")
        val store = VersionStore(home)
        val original = store.record(ArtifactType.SKILL, "greet", "original content", ProducedBy.HUMAN)
        store.record(ArtifactType.SKILL, "greet", "broken content", ProducedBy.WRITE_SKILL_TOOL)
        val lines = mutableListOf<String>()

        VersionsRevert(store, ArtifactType.SKILL, "greet", original.id) { lines.add(it) }.run()

        store.history(ArtifactType.SKILL, "greet") shouldHaveSize 3
        lines.first() shouldContain "reverted"
    }

    test("show prints a specific version's content and metadata") {
        val home = createTempDirectory("versions-cli-test")
        val store = VersionStore(home)
        val v1 = store.record(ArtifactType.SKILL, "greet", "the actual content", ProducedBy.HUMAN)
        val lines = mutableListOf<String>()

        VersionsShow(store, v1.id) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "the actual content"
        lines.joinToString("\n") shouldContain "HUMAN"
    }

    test("show reports failure for an unknown version id") {
        val home = createTempDirectory("versions-cli-test")
        val lines = mutableListOf<String>()

        VersionsShow(VersionStore(home), "does-not-exist") { lines.add(it) }.run()

        lines.first() shouldContain "No version found"
    }

    test("revert reports failure for an unknown version id") {
        val home = createTempDirectory("versions-cli-test")
        val lines = mutableListOf<String>()

        VersionsRevert(VersionStore(home), ArtifactType.SKILL, "greet", "does-not-exist") { lines.add(it) }.run()

        lines.first() shouldContain "No version found"
    }
})
