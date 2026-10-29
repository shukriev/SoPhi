package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.readText
import kotlin.io.path.writeText

private class Sub : CliktCommand(name = "sub") {
    val maxTokens by option("--max-tokens").int().default(1)
    override fun run() = Unit
}

private class Root(store: CliSettingsStore) : CliktCommand(name = "root", invokeWithoutSubcommand = true) {
    init { readSettingsFrom(store) }
    val maxTokens by option("--max-tokens").int().default(4096)
    val auto by option("--auto").flag("--no-auto", default = false)
    override fun run() = Unit
}

class CliSettingsTest : FunSpec({
    fun store(json: String? = null, warnings: MutableList<String> = mutableListOf()): CliSettingsStore {
        val file = tempdir().toPath().resolve("settings.json")
        json?.let { file.writeText(it) }
        return CliSettingsStore(file) { warnings += it }
    }

    test("a saved value becomes the default and a flag beats it") {
        val s = store("""{"max-tokens":"999"}""")
        Root(s).apply { parse(emptyList()) }.let { it.maxTokens shouldBe 999; it.effectiveValues()["max-tokens"] shouldBe "999" }
        Root(s).apply { parse(listOf("--max-tokens", "5")) }.maxTokens shouldBe 5
        Root(store()).apply { parse(emptyList()) }.maxTokens shouldBe 4096
    }

    test("a saved boolean flag is read") {
        Root(store("""{"auto":"true"}""")).apply { parse(emptyList()) }.auto shouldBe true
        Root(store("""{"auto":"false"}""")).apply { parse(emptyList()) }.auto shouldBe false
    }

    test("a saved on-flag can be switched off for one run") {
        Root(store("""{"auto":"true"}""")).apply { parse(listOf("--no-auto")) }.auto shouldBe false
    }

    test("every on/off start flag has an opposite, so a saved value can be overridden for one run") {
        val bools = CLI_SETTINGS.filter { it.kind == SettingKind.Bool }.map { "--${it.key}" }.toSet()
        SophiCli(Path.of("/nonexistent/settings.json")).registeredOptions()
            .filter { o -> o.names.any { it in bools } && o.secondaryNames.isEmpty() }
            .map { it.names }.shouldBeEmpty()
    }

    test("a bad or unknown saved value is dropped with a warning; the rest still apply, so sophi still starts") {
        val warnings = mutableListOf<String>()
        val s = store("""{"max-tokens":"abc","auto":"true","god-mode":"true","model":null}""", warnings)
        Root(s).apply { parse(emptyList()) }.let { it.maxTokens shouldBe 4096; it.auto shouldBe true }
        s.usable() shouldBe mapOf("auto" to "true")
        warnings.any { "max-tokens" in it } shouldBe true
    }

    test("god mode is never a saved setting") {
        CLI_SETTINGS.none { it.key == "god-mode" } shouldBe true
    }

    test("a subcommand's option of the same name doesn't read the file") {
        val sub = Sub()
        Root(store("""{"max-tokens":"999"}""")).subcommands(sub).parse(listOf("sub"))
        sub.maxTokens shouldBe 1
    }

    test("every start flag is a setting or explicitly excluded") {
        val names = SophiCli(Path.of("/nonexistent/settings.json")).registeredOptions()
            .flatMap { it.names }.filter { it.startsWith("--") }.map { it.removePrefix("--") }
            .filter { it != "help" }
        val known = CLI_SETTINGS.map { it.key }.toSet() + EXCLUDED_FROM_SETTINGS
        names.filter { it !in known && it.removePrefix("no-") !in known }.shouldBeEmpty()
        CLI_SETTINGS.map { it.key }.filter { it !in names }.shouldBeEmpty()
    }

    test("a malformed settings.json warns and gives no saved values") {
        val warnings = mutableListOf<String>()
        store("{nope", warnings).saved() shouldBe emptyMap()
        warnings.size shouldBe 1
    }

    test("a broken settings.json warns once, however often it is read") {
        val warnings = mutableListOf<String>()
        val s = store("{nope", warnings)
        repeat(5) { s.saved() }
        warnings.size shouldBe 1
    }

    test("a save over a broken settings.json is refused and the file is kept") {
        val s = store("{nope")
        s.save("model", "x") shouldContain "isn't valid JSON"
        s.file.readText() shouldBe "{nope"
    }

    test("validate rejects a bad choice, a non-number and a non-boolean, and normalises booleans") {
        val s = store()
        fun setting(key: String) = CLI_SETTINGS.single { it.key == key }
        s.validate(setting("sandbox"), "vm") shouldNotBe null
        s.validate(setting("sandbox"), "docker") shouldBe null
        s.validate(setting("max-tokens"), "lots") shouldNotBe null
        s.validate(setting("max-tokens"), "-1") shouldNotBe null
        s.validate(setting("llm-max-retries"), "0") shouldBe null
        s.validate(setting("memory"), "maybe") shouldNotBe null
        s.normalise(setting("memory"), "on") shouldBe "true"
        s.normalise(setting("memory"), "off") shouldBe "false"
    }

    test("save and reset round-trip and keep unknown keys") {
        val s = store("""{"future-thing":"x"}""")
        s.save("model", "gemma-4")
        s.saved() shouldBe mapOf("future-thing" to "x", "model" to "gemma-4")
        s.reset("model")
        s.saved() shouldBe mapOf("future-thing" to "x")
    }

    test("origin says flag, saved or default") {
        val s = store("""{"model":"gemma-4"}""")
        origin("model", listOf("--model", "x"), s) shouldBe "flag"
        origin("model", listOf("--model=x"), s) shouldBe "flag"
        origin("model", emptyList(), s) shouldBe "saved"
        origin("max-tokens", emptyList(), s) shouldBe "default"
    }
})
