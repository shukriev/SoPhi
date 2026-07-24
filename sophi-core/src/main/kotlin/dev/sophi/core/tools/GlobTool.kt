package dev.sophi.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.relativeTo

private const val DEFAULT_MAX_RESULTS = 200
private val DEFAULT_SKIP_DIRS = setOf(".git", "build", "target", "node_modules", ".gradle")

@Serializable
private data class GlobArgs(val pattern: String, val path: String? = null)

class GlobTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "glob"
    override val description = "Find files matching a glob pattern within the working directory"
    override val parametersJson = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern, e.g. '**/*.kt'"},"path":{"type":"string","description":"Subdirectory to search, relative to the working directory (default: whole working directory)"}},"required":["pattern"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<GlobArgs>(argumentsJson)
        val searchRoot = root.resolve(args.path ?: ".").normalize()
        require(searchRoot.startsWith(root)) { "Path escapes working directory: ${args.path}" }

        val matcher = root.fileSystem.getPathMatcher("glob:${args.pattern}")

        val matches = walkRegularFiles(searchRoot)
            .map { it.relativeTo(root) }
            .filter { relative -> DEFAULT_SKIP_DIRS.none { skip -> relative.any { part -> part.toString() == skip } } }
            .filter { matcher.matches(it) }
            .map { it.toString() }
            .sorted()

        return if (matches.isEmpty()) "No files found" else matches.take(DEFAULT_MAX_RESULTS).joinToString("\n")
    }
}
