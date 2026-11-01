package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.KeychainCredentialStore
import dev.sophi.core.credentials.SecretMask
import java.io.Console
import java.io.InputStream

/** What `sophi secret` does; never prints a value. */
class SecretActions(private val store: CredentialStore, private val echo: (String) -> Unit) {
    fun set(name: String, value: String?) {
        if (value.isNullOrEmpty()) return echo("Nothing stored: the value was empty.")
        store.set(name, value)
        echo("Stored $name. Use it in mcp.json as \${secret:$name}.")
        if (value.length < SecretMask.MIN_MASKED) echo("Under 8 characters: stored, but not masked in output.")
    }

    fun list() {
        val names = store.names()
        if (names.isEmpty()) echo("No secrets stored.") else names.forEach(echo)
    }

    fun remove(name: String) {
        store.remove(name)
        echo("Removed $name.")
    }
}

/** A hidden prompt on a terminal; else one line of [stdin], so `pbpaste | sophi secret set x` works. Never an argument. */
fun readSecretValue(name: String, console: Console? = System.console(), stdin: InputStream = System.`in`): String? =
    console?.readPassword("Value for $name: ")?.let(::String) ?: stdin.bufferedReader().readLine()

class SecretCommand : CliktCommand(name = "secret", help = "Store secrets for MCP servers in the macOS Keychain") {
    override fun run() = Unit
}

class SecretSetCommand : CliktCommand(name = "set", help = "Store a secret; the value is read from a hidden prompt or stdin") {
    private val name by argument()
    override fun run() = SecretActions(KeychainCredentialStore(), ::echo).set(name, readSecretValue(name))
}

class SecretListCommand : CliktCommand(name = "list", help = "List stored secret names") {
    override fun run() = SecretActions(KeychainCredentialStore(), ::echo).list()
}

class SecretRemoveCommand : CliktCommand(name = "remove", help = "Remove a stored secret") {
    private val name by argument()
    override fun run() = SecretActions(KeychainCredentialStore(), ::echo).remove(name)
}
