package dev.sophi.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The versioned artifact tournaments (Task 14+) mutate and compare. Not every field is reachable
 * from [RuntimeBuilder] alone: [systemPrompt]/[temperature]/[maxTokens]/[maxRecalledLessons] are
 * (via [RuntimeBuilder.configVersion]); [criticEnabled] is consumed directly by whichever code
 * constructs a `PlanRunnerConfig` (`ScheduleEngine`, `DecomposeGoalTool`'s `buildPlanRunner` path);
 * [topKSkills] and [toolDescriptionOverrides] are applied by [RuntimeBuilder] at tool-registration
 * time.
 */
@Serializable
data class HarnessConfig(
    val systemPrompt: String? = null,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val maxRecalledLessons: Int = 10,
    val criticEnabled: Boolean = true,
    val topKSkills: Int? = null,
    val toolDescriptionOverrides: Map<String, String> = emptyMap()
) {
    fun hash(): String {
        val json = Json { encodeDefaults = true }.encodeToString(serializer(), this)
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
