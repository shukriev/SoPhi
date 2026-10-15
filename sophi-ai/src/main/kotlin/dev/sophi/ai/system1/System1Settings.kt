package dev.sophi.ai.system1

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

enum class System1Mode { SYSTEM2, SHADOW, SYSTEM1 }

data class System1Settings(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val timeoutSeconds: Long = 20,
    val maxRetries: Int = 0,
    val modes: Map<String, System1Mode> = emptyMap(),
    val shadowSampleRate: Map<String, Double> = emptyMap()
) {
    fun modeOf(judgment: String): System1Mode = modes[judgment] ?: System1Mode.SYSTEM2
    fun sampleRateOf(judgment: String): Double = shadowSampleRate[judgment] ?: 1.0

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null (System 1 off) when [path] is missing or unreadable. Never throws: the companion runs unattended. */
        fun load(path: Path, onWarning: (String) -> Unit): System1Settings? {
            if (!Files.exists(path)) return null
            val file = runCatching { json.decodeFromString(System1File.serializer(), Files.readString(path)) }
                .getOrElse { onWarning("system1: ignoring unreadable $path (${it.message})"); return null }
            return file.toSettings(onWarning)
        }
    }
}

/** The on-disk shape: modes stay strings so one bad value warns instead of failing the whole file. */
@Serializable
private data class System1File(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val timeoutSeconds: Long = 20,
    val maxRetries: Int = 0,
    val modes: Map<String, String> = emptyMap(),
    val shadowSampleRate: Map<String, Double> = emptyMap()
)

private fun System1File.toSettings(onWarning: (String) -> Unit): System1Settings {
    (modes.keys + shadowSampleRate.keys).filter { it !in Judgments.ALL }.distinct()
        .forEach { onWarning("system1: unknown judgment '$it' in system1.json") }
    val parsed = modes.mapNotNull { (judgment, raw) ->
        val mode = System1Mode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        when {
            mode == null -> { onWarning("system1: unknown mode '$raw' for '$judgment', using SYSTEM2"); null }
            judgment in Judgments.SAFETY && mode == System1Mode.SYSTEM1 -> {
                onWarning("system1: '$judgment' is a safety judgment and cannot be SYSTEM1, using SHADOW")
                judgment to System1Mode.SHADOW
            }
            else -> judgment to mode
        }
    }.toMap()
    return System1Settings(
        baseUrl, model, apiKey, timeoutSeconds, maxRetries, parsed,
        shadowSampleRate.mapValues { it.value.coerceIn(0.0, 1.0) }
    )
}
