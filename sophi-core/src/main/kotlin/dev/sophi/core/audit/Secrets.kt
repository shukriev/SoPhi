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
    // scheme://user:password@host: the whole userinfo goes, up to the last @ before the host. A password may hold @ and
    // commas, but no comma after an @ (https://u:p@h.com,me@x.com keeps h.com). Each @-segment must itself be followed
    // by an @, so the loop is possessive and never backtracks.
    "url_credentials" to Regex(
        "(?<=://)[^/?#,\\s:@\"'\\\\{}]+:[^/?#\\s\"'\\\\{}@]+(?:@[^/?#,\\s\"'\\\\{}@]+(?=@))*+(?=@)",
    ),
    // curl -u / --user user:password, and curl's attached -uuser:password (not -u1000:1000, a docker uid:gid)
    "basic_auth" to Regex(
        "(?:(?<=(?:^|\\s)(?:-u|--user)[ =]{1,3})|(?<=(?:^|\\s)-u)(?=[^\\s\\d]))[^\\s:\"'\\\\{}]+:[^\\s\"'\\\\{}]+",
    ),
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
    "(?i)\\b([A-Za-z0-9_]*(?:password|passwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?|authorization)(?:_?(?:key|base|value|id|header|hash))*+)" +
        "(\\\\?[\"']?[ \\t]*[=:](?!=)[ \\t]*)" +
        "(?!(?:true|false|null|none|nil)(?![A-Za-z0-9_])|\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])|[A-Za-z_][A-Za-z0-9_.]*\\(|\\[REDACTED)" +
        // Unrolled and possessive, so the engine iterates instead of recursing once per character (a 100k-char value
        // used to throw StackOverflowError). A quote (plain or escaped) that never closes: the value up to whitespace.
        "(\\\\\"(?:(?!\\\\\").)*+\\\\\"|\\\\\"[^\\s'\"\\\\]++|\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'|[\"'][^\\s'\"\\\\]++|[^\\s'\"\\\\]+)",
)

/** A JSON key whose value is a secret: the keyword (plus optional key/base/value/id/header/hash suffixes) ends it, so max_tokens stays. */
private val SECRET_KEY = Regex(
    "(?:password|passwd|passphrase|secret|token|api[_-]?key|authorization|credentials?|private[_-]?key|access[_-]?key)" +
        "(?:_?(?:key|base|value|id|header|hash))*+$",
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

private val PRIVATE_KEY_BEGIN = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")
// A URL whose userinfo runs into the cut: no @, path or terminator after the scheme.
private val URL_TO_CUT = Regex("(?<=://)[^/?#\\s\"'\\\\{}@]*$")
private const val WINDOW_EDGE = 200
private const val MAX_JSON_DEPTH = 200
const val UNREDACTABLE = "[unredactable]"

/**
 * [redactSecrets] on at most [window] chars of [text]. When the window cut the text, a secret may have been cut below
 * its pattern's minimum length and survived, so the trailing [WINDOW_EDGE] chars go, then the token-shaped run, a URL
 * userinfo, or an unclosed private key ending there, and "…" marks the cut.
 */
fun redactWindowed(text: String, window: Int): String {
    if (text.length <= window) return redactSecrets(text)
    var out = redactSecrets(text.take(window)).dropLast(WINDOW_EDGE).dropLastWhile { it.isLetterOrDigit() || it in "._~+/=-" } // TOKEN_CHARS, the alphabet of every token pattern
    URL_TO_CUT.find(out)?.let { out = out.substring(0, it.range.first) }
    PRIVATE_KEY_BEGIN.find(out)?.let { out = out.substring(0, it.range.first) }
    return "$out…"
}

/**
 * For a tool's argument JSON: values under secret-named keys → "[REDACTED]", then redactSecrets on the rest. The whole
 * JSON is always parsed; [window] caps the text each redaction sees (see [redactWindowed]), so a long argument keeps its
 * key redaction. Later strings become "[…]" once [budget] output chars are kept or [work] input chars were redacted, and
 * so does anything nested deeper than [MAX_JSON_DEPTH]. Only input that isn't JSON at all is redacted as text; JSON that
 * still can't be redacted fails closed to [UNREDACTABLE].
 */
fun redactArguments(
    argumentsJson: String, window: Int = Int.MAX_VALUE, budget: Int = Int.MAX_VALUE, work: Int = Int.MAX_VALUE,
): String {
    val tree = try {
        Json.parseToJsonElement(argumentsJson)
    } catch (_: Exception) {
        return redactWindowed(argumentsJson, window)
    } catch (_: StackOverflowError) {
        return UNREDACTABLE
    }
    var left = budget
    var workLeft = work
    fun JsonElement.redacted(depth: Int): JsonElement = when {
        depth > MAX_JSON_DEPTH -> JsonPrimitive("[…]")
        this is JsonObject -> JsonObject(mapValues { (k, v) ->
            left -= k.length
            if (SECRET_KEY.containsMatchIn(k)) JsonPrimitive("[REDACTED]") else v.redacted(depth + 1)
        })
        this is JsonArray -> JsonArray(map { it.redacted(depth + 1) })
        this is JsonPrimitive && isString -> if (left <= 0 || workLeft <= 0) JsonPrimitive("[…]") else {
            workLeft -= minOf(content.length, window)
            JsonPrimitive(redactWindowed(content, window)).also { left -= it.content.length }
        }
        else -> this
    }
    return runCatching { tree.redacted(0).toString() }.getOrDefault(UNREDACTABLE)
}
