package dev.sophi.core.agent

import java.nio.file.Path
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
}
