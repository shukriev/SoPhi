package dev.sophi.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private const val MAX_FILE_BYTES = 1_000_000L

@Serializable
private data class FileReadArgs(val path: String)

class FileReadTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "read_file"
    override val description = "Read the contents of a UTF-8 text file within the working directory"
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to the file, relative to the working directory"}},"required":["path"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<FileReadArgs>(argumentsJson)
        val resolved = root.resolve(args.path).normalize()

        require(resolved.startsWith(root)) { "Path escapes working directory: ${args.path}" }

        if (!resolved.isRegularFile()) {
            throw FileNotFoundException("File not found: ${args.path}")
        }

        require(resolved.fileSize() <= MAX_FILE_BYTES) {
            "File too large (max $MAX_FILE_BYTES bytes): ${args.path}"
        }

        return resolved.readText()
    }
}
