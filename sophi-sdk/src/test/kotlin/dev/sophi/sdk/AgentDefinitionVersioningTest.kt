package dev.sophi.sdk

import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.versioning.ArtifactType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import dev.sophi.versioning.VersionStore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class AgentDefinitionVersioningTest : FunSpec({
    test("snapshotIfChanged records a version the first time a definition file is seen") {
        val versionStore = VersionStore(createTempDirectory("agent-def-versioning-test"))
        val file = createTempDirectory("agent-defs").resolve("explore.md")
        file.writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nSystem prompt.")
        val definition = AgentDefinitionLoader().loadFile(file)

        val result = snapshotIfChanged(file, definition, versionStore)

        result shouldBe versionStore.history(ArtifactType.AGENT_DEFINITION, "explore").single()
    }

    test("snapshotIfChanged is a no-op when content hasn't changed since the last version") {
        val versionStore = VersionStore(createTempDirectory("agent-def-versioning-test"))
        val file = createTempDirectory("agent-defs").resolve("explore.md")
        file.writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nSystem prompt.")
        val definition = AgentDefinitionLoader().loadFile(file)
        snapshotIfChanged(file, definition, versionStore)

        val result = snapshotIfChanged(file, definition, versionStore)

        result shouldBe null
        versionStore.history(ArtifactType.AGENT_DEFINITION, "explore") shouldHaveSize 1
    }

    test("snapshotIfChanged records a new version when the file content changed") {
        val versionStore = VersionStore(createTempDirectory("agent-def-versioning-test"))
        val file = createTempDirectory("agent-defs").resolve("explore.md")
        file.writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nOriginal prompt.")
        snapshotIfChanged(file, AgentDefinitionLoader().loadFile(file), versionStore)
        file.writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nUpdated prompt.")

        snapshotIfChanged(file, AgentDefinitionLoader().loadFile(file), versionStore)

        versionStore.history(ArtifactType.AGENT_DEFINITION, "explore") shouldHaveSize 2
    }

    test("loadAndVersionAgentDefinitions versions every file in the directory and returns the loaded definitions") {
        val versionStore = VersionStore(createTempDirectory("agent-def-versioning-test"))
        val dir = createTempDirectory("agent-defs")
        dir.resolve("explore.md").writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nPrompt A.")
        dir.resolve("coder.md").writeText("---\nname: coder\ndescription: d\nallowedTools: []\n---\nPrompt B.")

        val definitions = loadAndVersionAgentDefinitions(dir, versionStore)

        definitions.map { it.name }.toSet() shouldBe setOf("explore", "coder")
        versionStore.history(ArtifactType.AGENT_DEFINITION, "explore") shouldHaveSize 1
        versionStore.history(ArtifactType.AGENT_DEFINITION, "coder") shouldHaveSize 1
    }

    test("loadAndVersionAgentDefinitions does not re-version an unchanged file on a second call") {
        val versionStore = VersionStore(createTempDirectory("agent-def-versioning-test"))
        val dir = createTempDirectory("agent-defs")
        dir.resolve("explore.md").writeText("---\nname: explore\ndescription: d\nallowedTools: []\n---\nPrompt A.")

        loadAndVersionAgentDefinitions(dir, versionStore)
        loadAndVersionAgentDefinitions(dir, versionStore)

        versionStore.history(ArtifactType.AGENT_DEFINITION, "explore") shouldHaveSize 1
    }
})
