package dev.sophi.core.agent.plan

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * One JSONL file per plan id, one line per version — each line a complete, inspectable
 * snapshot (ADR-018's diff-based replanning point). No hardcoded ~/.sophi/... path — the
 * caller supplies plansDir, matching FileSessionManager's convention of never assuming a
 * cwd/home path inside sophi-core.
 */
class PlanLog(private val plansDir: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        plansDir.createDirectories()
    }

    fun append(plan: Plan) {
        val file = plansDir.resolve("${plan.id}.jsonl")
        val line = json.encodeToString(Plan.serializer(), plan)
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun versions(planId: String): List<Plan> {
        val file = plansDir.resolve("$planId.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(Plan.serializer(), it) }
    }
}
