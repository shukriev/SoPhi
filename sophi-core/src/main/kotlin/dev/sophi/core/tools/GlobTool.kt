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

        val matchers = zeroDirVariants(args.pattern).map { root.fileSystem.getPathMatcher("glob:$it") }

        val matches = walkRegularFiles(searchRoot)
            .filter { file ->
                val relativeToRoot = file.relativeTo(root)
                DEFAULT_SKIP_DIRS.none { skip -> relativeToRoot.any { part -> part.toString() == skip } }
            }
            // Matched relative to searchRoot (not root) so a bare pattern like "*.txt" behaves as
            // documented once "path" scopes into a subdirectory, instead of silently never matching.
            .filter { file -> file.relativeTo(searchRoot).let { rel -> matchers.any { it.matches(rel) } } }
            .map { it.relativeTo(root).toString() }
            .sorted()

        return if (matches.isEmpty()) "No files found" else matches.take(DEFAULT_MAX_RESULTS).joinToString("\n")
    }
}

// Java's glob makes each double-star-slash match >= 1 directory; bash globstar and gitignore
// also allow 0, so every occurrence is tried both ways (2^n patterns, n tiny in practice).
private fun zeroDirVariants(pattern: String): List<String> {
    val parts = pattern.split("**/")
    var variants = listOf(parts.first())
    for (part in parts.drop(1)) variants = variants.flatMap { listOf("$it**/$part", "$it$part") }
    return variants.distinct()
}
