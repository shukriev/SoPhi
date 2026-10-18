package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShellProcessTest : FunSpec({

    fun run(env: Map<String, String>, command: String): String {
        val process = shellProcess(command) { putAll(env) }.redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
    }

    // A comma-decimal locale made awk print 12,50 and the printf builtin reject 12.5 outright.
    test("numbers format with a dot under a comma-decimal LC_NUMERIC") {
        run(mapOf("LANG" to "de_DE.UTF-8", "LC_NUMERIC" to "de_DE.UTF-8"),
            """awk 'BEGIN{printf "%.2f\n", 12.5}'; printf '%.2f\n' 12.5""") shouldBe "12.50\n12.50"
    }

    test("an LC_ALL locale still gets dot decimals and keeps the rest of its settings") {
        run(mapOf("LC_ALL" to "de_DE.UTF-8"),
            """awk 'BEGIN{printf "%.2f\n", 12.5}'; echo "${'$'}LANG ${'$'}{LC_ALL-unset}"""") shouldBe
            "12.50\nde_DE.UTF-8 unset"
    }
})
