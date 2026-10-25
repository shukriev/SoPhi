package dev.sophi.core.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TOKEN_CHARS = "[A-Za-z0-9._~+/=-]"

/**
 * The token patterns shared with sophi-learning's Redactor. None of them can touch JSON structure (no quotes,
 * backslashes or braces are consumed), so the Redactor can run them over raw tool-call argument JSON and the result
 * still parses. Each repeats a single character class, never a variable-width group, so a long input can't overflow
 * the regex engine's stack.
 */
val SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
    "private_key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    "github_token" to Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{22,}"),
    "gitlab_token" to Regex("glpat-[A-Za-z0-9_-]{20,}"),
    "google_api_key" to Regex("AIza[0-9A-Za-z_-]{35}"),
    "stripe_key" to Regex("(?<![A-Za-z0-9])(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"),
    // scheme://user:password@host: the whole userinfo goes.
    "url_credentials" to Regex("(?<=://)[^/\\s:@\"'\\\\{}]+:[^/\\s@\"'\\\\{}]+(?=@)"),
    // curl -u / --user user:password
    "basic_auth" to Regex("(?<=(?:^|\\s)(?:-u|--user)[ =]{1,3})[^\\s:\"'\\\\{}]+:[^\\s\"'\\\\{}]+"),
    "aws_key" to Regex("AKIA[0-9A-Z]{16}"),
    "slack_token" to Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
    "jwt" to Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    // Not preceded by a letter or digit, so "task-management-..." / "risk-assessment-..." are left alone.
    "api_key" to Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}"),
    // After "Authorization:" any Bearer/Basic value goes; elsewhere only a token-shaped one (a digit or '=' in it, or 16+
    // chars), so prose like "basic configuration" or "the bearer of bad news" stays readable.
    "bearer" to Regex(
        "(?i)(?:(?<=\\bauthorization[:=]\\s{0,3})(?:bearer|basic)\\s+$TOKEN_CHARS+" +
            "|\\b(?:bearer|basic)\\s+(?:(?=$TOKEN_CHARS*[0-9=])$TOKEN_CHARS{4,}|$TOKEN_CHARS{16,}))",
    ),
)

/**
 * Audit-only: NAME=value, NAME: value and "name":"value" inside free text (.env content, JSON in a string). Keeps the
 * name and drops the value. Not in SECRET_PATTERNS, because it rewrites JSON structure. The keyword, plus an optional
 * key/base/value/id suffix, must END the name (API_KEY, SECRET_KEY_BASE, authToken; not max_tokens) and the value is skipped when it is a number, true/false/null or a call.
 * A quoted value is taken whole (backslash escapes respected), an unquoted or unterminated one runs to whitespace,
 * quote or backslash.
 */
private val ASSIGNMENT = Regex(
    "(?i)\\b([A-Za-z0-9_]*(?:password|passwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?)(?:_?(?:key|base|value|id))*)" +
        "(\\\\?[\"']?[ \\t]*[=:](?!=)[ \\t]*)" +
        "(?!(?:true|false|null|none|nil)(?![A-Za-z0-9_])|\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])|[A-Za-z_][A-Za-z0-9_.]*\\(|\\[REDACTED)" +
        // Unrolled and possessive, so the engine iterates instead of recursing once per character (a 100k-char value
        // used to throw StackOverflowError). Last before the bare value: a quote that never closes, value up to whitespace.
        "(\\\\\"(?:(?!\\\\\").)*+\\\\\"|\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'|[\"'][^\\s'\"\\\\]++|[^\\s'\"\\\\]+)",
)

/** A JSON key whose value is a secret: the keyword (plus an optional key/base/value/id suffix) ends it, so max_tokens stays. */
private val SECRET_KEY = Regex(
    "(?:password|passwd|passphrase|secret|token|api[_-]?key|authorization|credentials?|private[_-]?key|access[_-]?key)" +
        "(?:_?(?:key|base|value|id))*$",
    RegexOption.IGNORE_CASE,
)

/** Applies only the shared token patterns: each match → `[REDACTED:<type>]`. [onHit] gets the type per match. */
fun redactTokens(text: String, onHit: (String) -> Unit = {}): String =
    SECRET_PATTERNS.fold(text) { out, (type, regex) ->
        regex.replace(out) { onHit(type); "[REDACTED:$type]" }
    }

/** Token patterns plus the audit-only assignment pattern. */
fun redactSecrets(text: String): String =
    ASSIGNMENT.replace(redactTokens(text)) { m ->
        val v = m.groupValues[3]
        val open = when {
            v.startsWith("\\\"") -> 2
            v[0] == '"' || v[0] == '\'' -> 1
            else -> 0
        }
        // An unterminated quote has no closing one to keep.
        val close = if (open > 0 && v.length >= 2 * open && v.endsWith(v.substring(0, open))) open else 0
        if (v.substring(open).startsWith("[REDACTED")) m.value
        else m.groupValues[1] + m.groupValues[2] + v.substring(0, open) + "[REDACTED:secret]" + v.substring(v.length - close)
    }

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
