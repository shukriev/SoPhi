package dev.sophi.gateway

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class PeopleTest : FunSpec({
    test("owner id is stable") {
        val file = createTempDirectory("people").resolve("people.json")
        val first = People(file).owner()
        first.id.shouldNotBeBlank()
        People(file).owner().id shouldBe first.id
    }

    test("an old people.json reads as the owner") {
        val file = createTempDirectory("people").resolve("people.json")
        file.writeText("""[{"id":"o1","name":"You"}]""")
        People(file).owner() shouldBe Person("o1", "You", Role.OWNER)
    }

    test("add and remove a member") {
        val file = createTempDirectory("people").resolve("people.json")
        val people = People(file)
        val a = people.add("Alex")
        a.role shouldBe Role.MEMBER
        People(file).all().map { it.name } shouldBe listOf("You", "Alex")
        people.get(a.id) shouldBe a
        people.remove(a.id) shouldBe true
        people.all().size shouldBe 1
    }

    test("the owner can't be removed") {
        val people = People(createTempDirectory("people").resolve("people.json"))
        people.remove(people.owner().id) shouldBe false
    }
})
