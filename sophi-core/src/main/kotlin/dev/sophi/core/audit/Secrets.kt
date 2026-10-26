package dev.sophi.core.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
// A URL whose userinfo may run into the cut: no path or terminator after the scheme (an @ can be part of the password).
private val URL_TO_CUT = Regex("(?<=://)[^/?#\\s\"'\\\\{}]*$")
private const val WINDOW_EDGE = 200
private const val MAX_JSON_DEPTH = 200
// Work charged per key or string on top of its length, so empty values and many short ones still use up the budget.
private const val WORK_PER_ITEM = 32
const val UNREDACTABLE = "[unredactable]"
// JSON structure anywhere in text that didn't parse (a ```json fence, a cut JSON string, pretty-printed with \n escapes).
private val JSON_INSIDE = Regex("[{\\[](?:\\s|\\\\[nrt])*\\\\*\"") // any escaping depth: JSON in a string in a string
// JSON inside a string inside JSON is walked as JSON up to this many levels; deeper fails closed.
private const val MAX_JSON_IN_STRING = 3
private const val MAX_NUMBER_LENGTH = 64
private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

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

private fun withoutBom(text: String) = text.removePrefix("\uFEFF")

/** Starts with { or [ (after a BOM and whitespace), or holds {" / [" anywhere: JSON that must parse or fail closed. */
private fun looksLikeJson(text: String): Boolean {
    val start = withoutBom(text).trimStart().firstOrNull()
    return start == '{' || start == '[' || JSON_INSIDE.containsMatchIn(text)
}

private fun parseOrNull(text: String): JsonElement? = try {
    Json.parseToJsonElement(withoutBom(text))
} catch (_: Exception) {
    null
} catch (_: StackOverflowError) {
    null
}

/**
 * For a tool's argument JSON: values under secret-named keys → "[REDACTED]", then redactSecrets on the rest, and token
 * patterns on the keys. A string value that is itself JSON is redacted the same way (up to [MAX_JSON_IN_STRING] levels)
 * and written back as a string. The whole JSON is always parsed; [window] caps the text each redaction sees (see
 * [redactWindowed]), so a long argument keeps its key redaction. Once [budget] output chars are kept or [work] is used
 * (each key, string and number costs its windowed length plus [WORK_PER_ITEM]), the rest of each object or array is
 * replaced by one "[…]"; so is anything nested deeper than [MAX_JSON_DEPTH] and any number over [MAX_NUMBER_LENGTH]
 * chars. Only input that doesn't look like JSON (see [looksLikeJson]) is redacted as text; anything that does but can't
 * be redacted fails closed to [UNREDACTABLE] (the whole argument, or just that string value). Keys that redact alike
 * are numbered (#2, #3), and unquoted literals are redacted as strings.
 */
fun redactArguments(
    argumentsJson: String, window: Int = Int.MAX_VALUE, budget: Int = Int.MAX_VALUE, work: Int = Int.MAX_VALUE,
): String {
    val tree = parseOrNull(argumentsJson)
        ?: return if (looksLikeJson(argumentsJson)) UNREDACTABLE else redactWindowed(argumentsJson, window)
    var left = budget
    var workLeft = work
    fun exhausted() = left <= 0 || workLeft <= 0
    fun charge(text: String) { workLeft -= minOf(text.length, window) + WORK_PER_ITEM }
    val cut = JsonPrimitive("[…]")
    fun JsonElement.redacted(depth: Int, nest: Int): JsonElement = when {
        depth > MAX_JSON_DEPTH -> cut
        this is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>()
            for ((k, v) in this) {
                if (exhausted()) { out["…"] = cut; break }
                charge(k)
                var key = redactWindowed(k, window).also { left -= it.length }
                if (key in out) key = generateSequence(2) { it + 1 }.map { "$key#$it" }.first { it !in out } // two keys redacted alike
                // A key too long to scan whole is treated as secret-named (a suffix scan would miss the suffix bomb).
                out[key] = if (k.length > window || SECRET_KEY.containsMatchIn(k)) JsonPrimitive("[REDACTED]") else v.redacted(depth + 1, nest)
            }
            JsonObject(out)
        }
        this is JsonArray -> {
            val out = ArrayList<JsonElement>()
            for (v in this) {
                if (exhausted()) { out += cut; break }
                out += v.redacted(depth + 1, nest)
            }
            JsonArray(out)
        }
        this is JsonNull || this is JsonPrimitive && !isString && (content == "true" || content == "false") -> this
        exhausted() -> cut
        // The parser also accepts unquoted literals (AKIA..., API_KEY=x); only real numbers pass as-is, and not huge ones.
        this is JsonPrimitive && !isString && content.length > MAX_NUMBER_LENGTH -> cut
        this is JsonPrimitive && !isString && JSON_NUMBER.matches(content) -> also { charge(content); left -= content.length }
        else -> {
            val text = (this as JsonPrimitive).content
            charge(text)
            val redacted = when {
                !looksLikeJson(text) -> redactWindowed(text, window)
                nest >= MAX_JSON_IN_STRING -> UNREDACTABLE
                else -> parseOrNull(text)?.redacted(depth + 1, nest + 1)?.toString() ?: UNREDACTABLE
            }
            JsonPrimitive(redacted).also { left -= redacted.length }
        }
    }
    return runCatching { tree.redacted(0, 0).toString() }.getOrDefault(UNREDACTABLE)
}
