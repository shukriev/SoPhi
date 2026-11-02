package dev.sophi.gateway

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.io.path.createTempDirectory

class PeopleTest : FunSpec({
    test("owner id is stable") {
        val file = createTempDirectory("people").resolve("people.json")
        val first = People(file).owner()
        first.id.shouldNotBeBlank()
        People(file).owner().id shouldBe first.id
    }
})
