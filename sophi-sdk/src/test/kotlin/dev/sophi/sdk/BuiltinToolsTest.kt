package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class BuiltinToolsTest : FunSpec({
    test("buildBuiltinTools registers get_current_datetime") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names shouldContain "get_current_datetime"
    }

    test("buildBuiltinTools registers invoke_claude_code") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names shouldContain "invoke_claude_code"
    }

    test("decompose_goal is a session-bound tool, so it is absent from the static builtin set") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names.contains("decompose_goal") shouldBe false
    }
})
