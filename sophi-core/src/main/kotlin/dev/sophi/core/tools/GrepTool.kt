package dev.sophi.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo

private const val DEFAULT_MAX_RESULTS = 200
private val DEFAULT_SKIP_DIRS = setOf(".git", "build", "target", "node_modules", ".gradle")

@Serializable
private data class GrepArgs(
    val pattern: String,
    val path: String? = null,
    val filePattern: String? = null,
    val maxResults: Int? = null
)

class GrepTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "grep"
    override val description = "Search file contents for a regex pattern within the working directory"
    override val parametersJson = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern to search for"},"path":{"type":"string","description":"Subdirectory to search, relative to the working directory (default: whole working directory)"},"filePattern":{"type":"string","description":"Glob filter applied to file names, e.g. '*.kt'"},"maxResults":{"type":"integer","description":"Maximum number of matching lines to return (default 200)"}},"required":["pattern"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<GrepArgs>(argumentsJson)
        val searchRoot = root.resolve(args.path ?: ".").normalize()
        require(searchRoot.startsWith(root)) { "Path escapes working directory: ${args.path}" }

        val regex = Regex(args.pattern)
        val maxResults = args.maxResults ?: DEFAULT_MAX_RESULTS
        val fileMatcher = args.filePattern?.let { root.fileSystem.getPathMatcher("glob:$it") }

        val allMatches = walkRegularFiles(searchRoot)
            .asSequence()
            .filter { path ->
                val relative = path.relativeTo(root)
                DEFAULT_SKIP_DIRS.none { skip -> relative.any { part -> part.toString() == skip } }
            }
            .filter { fileMatcher == null || fileMatcher.matches(it.fileName) }
            .flatMap { file ->
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                lines.withIndex()
                    .filter { (_, line) -> regex.containsMatchIn(line) }
                    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: $line" }
            }
            .take(maxResults + 1)
            .toList()

        if (allMatches.isEmpty()) return "No matches found"
        val truncated = allMatches.size > maxResults
        val shown = allMatches.take(maxResults)
        return if (truncated) shown.joinToString("\n") + "\n... more matches truncated"
        else shown.joinToString("\n")
    }
}
