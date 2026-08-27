package dev.sophi.core.agent.eval

import com.charleskorn.kaml.Yaml
import dev.sophi.core.agent.plan.StopCondition
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Serializable
private data class EvalCaseFile(
    val id: String,
    val goalPrompt: String,
    val check: StopCondition.ShellCheck,
    val maxIterations: Int = 5
)

/**
 * A hand-authored (or, in a later phase, harvested) eval case, checked into the repo — never only
 * in a database. [category] is derived from the case file's parent directory name, not a field in
 * the file itself, so organizing cases into categories is just moving files between directories.
 */
data class EvalCase(val id: String, val category: String, val scenario: EvalScenario)

/**
 * Loads every `*.yaml` case under [directory], one level of category subdirectories deep (e.g.
 * `evals/coding/case-1.yaml` has category "coding"). Returns an empty list for a directory with no
 * category subdirectories, or that doesn't exist at all.
 */
fun loadEvalCases(directory: Path): List<EvalCase> {
    if (!directory.exists() || !directory.isDirectory()) return emptyList()
    return directory.listDirectoryEntries().filter { it.isDirectory() }.flatMap { categoryDir ->
        categoryDir.listDirectoryEntries("*.yaml").filter { it.isRegularFile() }.map { file ->
            val parsed = Yaml.default.decodeFromString(EvalCaseFile.serializer(), file.readText())
            EvalCase(
                id = parsed.id,
                category = categoryDir.name,
                scenario = EvalScenario(
                    name = parsed.id, goalPrompt = parsed.goalPrompt,
                    check = parsed.check, maxIterations = parsed.maxIterations
                )
            )
        }
    }
}
