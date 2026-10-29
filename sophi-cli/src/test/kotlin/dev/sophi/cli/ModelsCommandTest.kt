package dev.sophi.cli

import com.github.ajalt.clikt.testing.test
import dev.sophi.sdk.ModelCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ModelsCommandTest : FunSpec({
    val catalog = ModelCatalog.parse(
        """{"anthropic":{"claude-x":{"c":200000,"i":3,"p":15,"t":1,"x":1}},"openai":{"gpt-y":{"c":128000}}}"""
    )

    test("sophi models <query> lists matching entries with prices and capabilities") {
        val out = ModelsCommand(catalog = { catalog }).test("claude").output
        out shouldContain "anthropic/claude-x"
        out shouldContain "$3.00/$15.00"
        out shouldContain "tools"
        out shouldNotContain "gpt-y"
    }

    test("an entry without a price shows a dash") {
        ModelsCommand(catalog = { catalog }).test("gpt").output shouldContain "-/-"
    }
})
