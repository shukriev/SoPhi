package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

class ScriptedInputSourceTest : FunSpec({
    test("readLine() returns each scripted line in order, then null") {
        val source = ScriptedInputSource(listOf("one", "two"))

        source.readLine() shouldBe "one"
        source.readLine() shouldBe "two"
        source.readLine() shouldBe null
    }

    test("awaitEsc() suspends until signalEsc() is called") {
        val source = ScriptedInputSource(emptyList())
        var completed = false

        val job = async { source.awaitEsc(); completed = true }
        completed shouldBe false

        source.signalEsc()
        withTimeout(1000) { job.await() }
        completed shouldBe true
    }
})
