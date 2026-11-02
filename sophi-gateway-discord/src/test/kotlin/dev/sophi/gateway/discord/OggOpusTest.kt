package dev.sophi.gateway.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

private fun fixture(name: String): Path = Path.of(OggOpusTest::class.java.getResource("/discord/$name")!!.toURI())

class OggOpusTest : FunSpec({
    test("ffmpeg ogg decodes to 16k wav") {
        val wav = createTempDirectory("ogg").resolve("out.wav")
        oggOpusToWav16k(fixture("voice-3s.ogg"), wav).getOrThrow()
        val b = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN)
        String(Files.readAllBytes(wav), 0, 4, Charsets.US_ASCII) shouldBe "RIFF"
        b.getShort(22).toInt() shouldBe 1          // channels
        b.getInt(24) shouldBe 16000                 // sample rate
        b.getShort(34).toInt() shouldBe 16          // bits per sample
        val samples = (Files.size(wav) - 44) / 2
        samples.toDouble() shouldBe (48_000.0 plusOrMinus 4_800.0)
    }

    test("truncated ogg") {
        val wav = createTempDirectory("ogg").resolve("out.wav")
        oggOpusToWav16k(fixture("voice-truncated.ogg"), wav).isFailure shouldBe true
        oggOpusToWav16k(fixture("README.md"), wav).isFailure shouldBe true
    }
})
