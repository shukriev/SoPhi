package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

class BuiltinToolsTest : FunSpec({
    test("buildBuiltinTools registers get_current_datetime") {
        val names = buildBuiltinTools(braveApiKeyOption = null).map { it.name }
        names shouldContain "get_current_datetime"
    }
})
