package dev.sophi.cli

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class System1BatchTest : FunSpec({
    val input = """{"turn":{"userInput":"hi there","assistantReply":"hello"}}"""
    fun run(block: System1Batch.() -> Unit): List<JsonObject> {
        val out = mutableListOf<String>()
        System1Batch { out += it }.block()
        return out.map { Json.parseToJsonElement(it).jsonObject }
    }

    test("render writes one production request per row, in order") {
        val rows = run { render("encoder", "small", sequenceOf("""{"id":"a","input":$input}""", """{"id":"b","input":$input}""")) }
        rows.map { it["id"]!!.jsonPrimitive.content } shouldBe listOf("a", "b")
        val req = rows[0]["request"]!!.jsonObject
        req["judgment"]!!.jsonPrimitive.content shouldBe "encoder"
        req["reasoningEffort"]!!.jsonPrimitive.content shouldBe "none"
        req["maxTokens"]!!.jsonPrimitive.content shouldBe "1024"
        val message = req["messages"]!!.jsonArray.single().jsonObject
        message["role"]!!.jsonPrimitive.content shouldBe "user"
        message["content"]!!.jsonPrimitive.content shouldContain "USER: hi there"
    }

    test("a malformed line yields an error line and the batch continues") {
        val rows = run { render("encoder", "m", sequenceOf("not json", """{"id":"x","input":{}}""", """{"id":"b","input":$input}""")) }
        rows[0]["error"].shouldNotBeNull()
        rows[1]["id"]!!.jsonPrimitive.content shouldBe "x"
        rows[1]["error"].shouldNotBeNull()
        rows[2]["request"].shouldNotBeNull()
    }

    test("blank lines are skipped") {
        run { render("encoder", "m", sequenceOf("", """{"id":"a","input":$input}""", "  ")) }.size shouldBe 1
    }

    test("parse returns canonical output, or null when production would reject the reply") {
        val rows = run { parse("encoder", sequenceOf("""{"id":"a","reply":"[]"}""", """{"id":"b","reply":""}""")) }
        rows[0]["output"]!!.jsonObject["memories"]!!.jsonArray.size shouldBe 0
        rows[1]["output"] shouldBe JsonNull
    }

    test("an unknown judgment is a usage error naming the known ones") {
        shouldThrow<UsageError> { System1Batch {}.render("nope", "m", emptySequence()) }.message!! shouldContain "encoder"
    }

    // A bare `java -jar … system1 render` under LANG=C used the platform charset and wrote Cyrillic as "?".
    test("stdin and stdout are UTF-8 whatever the platform charset") {
        val bytes = java.io.ByteArrayOutputStream()
        val line = """{"id":"bg","input":{"turn":{"userInput":"Мила und Grüße"}}}"""
        System1Batch(echo = utf8Writer(bytes))
            .render("encoder", "m", utf8Lines(java.io.ByteArrayInputStream(line.toByteArray(Charsets.UTF_8))))
        bytes.toString(Charsets.UTF_8) shouldContain "USER: Мила und Grüße"
    }
})
