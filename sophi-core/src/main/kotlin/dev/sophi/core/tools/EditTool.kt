package dev.sophi.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class EditArgs(
    val path: String,
    val old_string: String,
    val new_string: String,
    val replace_all: Boolean = false
)

class EditTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "edit_file"
    override val description = "Replace an exact string in a file within the working directory"
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val args = runCatching { json.decodeFromString<EditArgs>(argumentsJson) }.getOrNull()
            ?: return RuleVerdict.HIGH_RISK
        return classifyPathRisk(root, args.path)
    }
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to the file, relative to the working directory"},"old_string":{"type":"string","description":"Exact text to replace"},"new_string":{"type":"string","description":"Replacement text"},"replace_all":{"type":"boolean","description":"Replace every occurrence instead of requiring exactly one (default false)"}},"required":["path","old_string","new_string"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<EditArgs>(argumentsJson)
        val resolved = root.resolve(args.path).normalize()
        require(resolved.startsWith(root)) { "Path escapes working directory: ${args.path}" }

        if (!resolved.isRegularFile()) {
            throw FileNotFoundException("File not found: ${args.path}")
        }

        val content = resolved.readText()
        val occurrences = content.split(args.old_string).size - 1

        if (occurrences == 0) {
            return "Error: old_string not found in ${args.path}"
        }
        if (occurrences > 1 && !args.replace_all) {
            return "Error: old_string found $occurrences times in ${args.path}; " +
                "add more surrounding context or set replace_all to true"
        }

        val updated = if (args.replace_all) {
            content.replace(args.old_string, args.new_string)
        } else {
            content.replaceFirst(args.old_string, args.new_string)
        }
        resolved.writeText(updated)

        return if (args.replace_all) "Replaced $occurrences occurrence(s) in ${args.path}"
        else "Replaced 1 occurrence in ${args.path}"
    }
}
