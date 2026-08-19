package dev.sophi.core.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class AgentDefinitionLoader {
    fun load(directory: Path): List<AgentDefinition> {
        require(directory.isDirectory()) { "Not a directory: $directory" }
        return directory.listDirectoryEntries("*.md")
            .filter { it.isRegularFile() }
            .map { loadFile(it) }
            .sortedBy { it.name }
    }

    fun loadFile(file: Path): AgentDefinition =
        parseAgentDefinition(file.readText(), file)

    /**
     * [load], but never throws: creates [directory] first if it doesn't exist, and on any
     * failure (a malformed file, [load]'s own directory check) reports [onWarning] and returns
     * an empty list instead. The one fail-soft agent-definition loading path every host
     * (interactive CLI session, CLI scheduler daemon, sophi-companion) converges on.
     */
    fun loadOrWarn(directory: Path, onWarning: (String) -> Unit = { System.err.println(it) }): List<AgentDefinition> =
        runCatching {
            directory.createDirectories()
            load(directory)
        }.getOrElse { e ->
            onWarning("failed to load agent definitions from $directory: ${e.message}")
            emptyList()
        }
}
