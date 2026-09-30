package dev.sophi.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

private const val MAX_CONTENT_BYTES = 1_000_000L

@Serializable
private data class FileWriteArgs(val path: String, val content: String)

class FileWriteTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "write_file"
    override val description = "Write UTF-8 text content to a file within the working directory, creating it (and parent directories) if needed"
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        // Unparseable: execute() decodes the same type and throws before doing anything, so don't prompt —
        // let it fail with the parse error the model can act on. (riskLevel still fails closed; ADR-027.)
        val args = runCatching { json.decodeFromString<FileWriteArgs>(argumentsJson) }.getOrNull()
            ?: return RuleVerdict.LOW_RISK
        return classifyPathRisk(root, args.path)
    }
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to the file, relative to the working directory"},"content":{"type":"string","description":"UTF-8 text content to write"}},"required":["path","content"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<FileWriteArgs>(argumentsJson)
        val resolved = root.resolve(args.path).normalize()

        require(resolved.startsWith(root)) { "Path escapes working directory: ${args.path}" }

        val contentBytes = args.content.toByteArray(Charsets.UTF_8)
        require(contentBytes.size <= MAX_CONTENT_BYTES) {
            "Content too large (max $MAX_CONTENT_BYTES bytes): ${args.path}"
        }

        require(!resolved.isDirectory()) { "Path is a directory: ${args.path}" }

        resolved.parent?.createDirectories()
        resolved.writeText(args.content)

        return withTestHint("Wrote ${contentBytes.size} bytes to ${args.path}", args.path)
    }
}
