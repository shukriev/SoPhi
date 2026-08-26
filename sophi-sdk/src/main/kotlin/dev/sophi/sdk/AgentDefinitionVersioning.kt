package dev.sophi.sdk

import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.Version
import dev.sophi.versioning.VersionStore
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Lives in sophi-sdk, not sophi-core (where [AgentDefinitionLoader] itself lives), because
 * sophi-core is deliberately kept minimal — it depends on nothing but sophi-ai today — and
 * sophi-versioning is not worth adding to that short list. sophi-sdk already depends on both.
 *
 * Keyed by [AgentDefinition.name] (the YAML frontmatter field, not the filename) — the same
 * identity `SubagentTool`/`AgentDefinitionLoader.load()`'s own sort already treat as canonical.
 */
fun snapshotIfChanged(
    file: Path,
    definition: AgentDefinition,
    versionStore: VersionStore,
    producedBy: ProducedBy = ProducedBy.HUMAN
): Version? {
    val content = file.readText()
    val latest = versionStore.history(ArtifactType.AGENT_DEFINITION, definition.name).maxByOrNull { it.createdAtMs }
    if (latest?.content == content) return null
    return versionStore.record(ArtifactType.AGENT_DEFINITION, definition.name, content, producedBy)
}

/**
 * Versions every `*.md` file in [directory] against [versionStore] (idempotent — unchanged files
 * are skipped), then returns the same fail-soft-loaded list [AgentDefinitionLoader.loadOrWarn]
 * already provides. This is how the orchestrator's own definition file — and every other agent
 * definition — gets captured on every load, since no dedicated write path exists for these files.
 */
fun loadAndVersionAgentDefinitions(
    directory: Path,
    versionStore: VersionStore,
    onWarning: (String) -> Unit = {}
): List<AgentDefinition> {
    val loader = AgentDefinitionLoader()
    runCatching {
        directory.listDirectoryEntries("*.md").filter { it.isRegularFile() }.forEach { file ->
            runCatching { snapshotIfChanged(file, loader.loadFile(file), versionStore) }
        }
    }
    return loader.loadOrWarn(directory, onWarning)
}
