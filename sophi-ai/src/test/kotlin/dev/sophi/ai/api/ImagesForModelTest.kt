package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class ImagesForModelTest : FunSpec({
    fun img(n: Int) = ImageRef("/tmp/i$n.png")

    test("a model that can't see gets a note for each image and no images") {
        val out = prepareForModel(listOf(Message(MessageRole.USER, "what's this?", images = listOf(img(1), img(2)))), canSee = false)
        out.single().images shouldBe emptyList()
        out.single().content shouldBe "what's this?\n[image left out: this model can't see images]\n[image left out: this model can't see images]"
    }

    test("a model that can see gets only the newest four images in the whole request") {
        val out = prepareForModel(listOf(
            Message(MessageRole.USER, "first", images = listOf(img(1), img(2), img(3))),
            Message(MessageRole.ASSISTANT, "ok"),
            Message(MessageRole.USER, "second", images = listOf(img(4), img(5))),
        ), canSee = true)
        out[2].images shouldBe listOf(img(4), img(5))
        out[0].images shouldBe listOf(img(2), img(3))
        out[0].content shouldBe "first\n[image left out: earlier image]"
    }

    test("messages without images pass through unchanged") {
        val msgs = listOf(Message(MessageRole.USER, "hi"), Message(MessageRole.ASSISTANT, "yo"))
        prepareForModel(msgs, canSee = false) shouldBe msgs
    }

    test("the type comes from the first bytes") {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        sniffImageType(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)) shouldBe "image/png"
        sniffImageType(b(0xFF, 0xD8, 0xFF, 0xE0)) shouldBe "image/jpeg"
        sniffImageType("GIF89a".toByteArray()) shouldBe "image/gif"
        sniffImageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)) shouldBe "image/webp"
        sniffImageType("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)) shouldBe null
        sniffImageType("hello".toByteArray()) shouldBe null
        sniffImageType(ByteArray(0)) shouldBe null
    }
})
