package dev.sophi.core.credentials

import dev.sophi.core.tools.SystemDesktop
import kotlinx.coroutines.runBlocking

/** The actor every entry has until a host says who is calling (the paid module's SSO fills it in). */
const val LOCAL_ACTOR = "local"

/** Where secrets for connectors live. Values never go to prompts, tools or logs; only to MCP servers. */
interface CredentialStore {
    fun get(name: String, actor: String = LOCAL_ACTOR): String?
    fun set(name: String, value: String, actor: String = LOCAL_ACTOR)
    fun remove(name: String, actor: String = LOCAL_ACTOR)
    fun names(actor: String = LOCAL_ACTOR): List<String>
}

/**
 * The macOS Keychain through `security`. A value is written through `security -i` on stdin, so it never appears in a
 * process's arguments. Every value read or written joins [mask], so it can't leak into what Sophi records.
 */
class KeychainCredentialStore(
    private val service: String = "dev.sophi.credentials",
    private val mask: SecretMask? = null,
    private val run: (List<String>, String) -> Pair<Int, String> = { args, stdin -> runBlocking { SystemDesktop.runCommand(args, stdin) } },
) : CredentialStore {
    override fun get(name: String, actor: String): String? {
        val (code, out) = run(listOf("security", "find-generic-password", "-s", service, "-a", account(name, actor), "-w"), "")
        return out.trimEnd('\n').takeIf { code == 0 && it.isNotEmpty() }?.also { mask?.add(it) }
    }

    override fun set(name: String, value: String, actor: String) {
        // One command per line: a line break (say, in a token a server sent) could start a second security command.
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "A secret can't contain line breaks." }
        val acct = account(name, actor)
        require(acct.none { it.isISOControl() }) { "A secret's name can't contain control characters." }
        val line = "add-generic-password -U -s ${quote(service)} -a ${quote(acct)} -w ${quote(value)}\n"
        // ponytail: `security -i` garbles input lines past ~4 KB, so a longer value is refused; a value that big would
        // need the Security framework through JNA instead of the command.
        require(line.toByteArray().size <= MAX_LINE) { "That secret is too long for the Keychain command (over ${MAX_LINE / 1000} KB)." }
        val (code, out) = run(listOf("security", "-i"), line)
        check(code == 0) { "Couldn't save it in the Keychain: ${out.trim().ifEmpty { "exit $code" }}" }
        mask?.add(value)
    }

    override fun remove(name: String, actor: String) {
        run(listOf("security", "delete-generic-password", "-s", service, "-a", account(name, actor)), "")
    }

    override fun names(actor: String): List<String> {
        val (code, out) = run(listOf("security", "dump-keychain"), "")
        if (code != 0) return emptyList()
        val prefix = if (actor == LOCAL_ACTOR) "" else "$actor/"
        // Each entry starts at "keychain:"; its attributes hold "acct" and "svce" in any order.
        return out.split("keychain:").mapNotNull { entry ->
            val acct = attr("acct", entry)
            acct?.takeIf { attr("svce", entry) == service }
        }.filter { if (prefix.isEmpty()) '/' !in it else it.startsWith(prefix) }.map { it.removePrefix(prefix) }.sorted()
    }

    private companion object { const val MAX_LINE = 4_000 }

    private fun account(name: String, actor: String) = if (actor == LOCAL_ACTOR) name else "$actor/$name"
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun attr(name: String, entry: String) =
        Regex("\"$name\"<blob>=\"((?:[^\"\\\\]|\\\\.)*)\"").find(entry)?.groupValues?.get(1)
}

/** Live secret values, masked out of text before Sophi shows or records it. Values under 8 chars are left alone. */
class SecretMask {
    private val values = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun add(value: String) { if (value.length >= MIN_MASKED) values += value }

    fun mask(text: String): String =
        values.sortedByDescending { it.length }.fold(text) { out, v -> out.replace(v, "[REDACTED:secret]") }

    companion object { const val MIN_MASKED = 8 }
}
