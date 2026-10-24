package dev.sophi.core.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The token patterns shared with sophi-learning's Redactor: private_key, github_token, aws_key, slack_token, jwt, api_key, bearer. */
val SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
    "private_key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    "github_token" to Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
    "aws_key" to Regex("AKIA[0-9A-Z]{16}"),
    "slack_token" to Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
    "jwt" to Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    "api_key" to Regex("sk-[A-Za-z0-9]{20,}"),
    "bearer" to Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}"),
)

private val SECRET_KEY = Regex(
    "password|passwd|passphrase|secret|token|api[_-]?key|apikey|authorization|credential|private[_-]?key|access[_-]?key",
    RegexOption.IGNORE_CASE,
)

fun redactSecrets(text: String): String =
    SECRET_PATTERNS.fold(text) { out, (type, regex) -> regex.replace(out, "[REDACTED:$type]") }

/** For a tool's argument JSON: values under secret-named keys → "[REDACTED]", then redactSecrets on the rest; non-JSON input → redactSecrets(text). */
fun redactArguments(argumentsJson: String): String {
    val tree = try {
        Json.parseToJsonElement(argumentsJson)
    } catch (_: Exception) {
        return redactSecrets(argumentsJson)
    }
    return tree.redacted().toString()
}

private fun JsonElement.redacted(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (k, v) -> if (SECRET_KEY.containsMatchIn(k)) JsonPrimitive("[REDACTED]") else v.redacted() })
    is JsonArray -> JsonArray(map { it.redacted() })
    is JsonPrimitive -> if (isString) JsonPrimitive(redactSecrets(content)) else this
}
