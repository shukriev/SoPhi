package dev.sophi.gateway.discord

import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

private const val RATE = 16_000
private const val MAX_FRAME = RATE * 120 / 1000   // Opus frames are at most 120 ms

/**
 * A Discord voice message (Ogg Opus) as the 16 kHz 16-bit mono WAV whisper reads, the same format
 * the Mac's own recordings use. A cut-short or non-Opus file fails; nothing is thrown.
 */
fun oggOpusToWav16k(ogg: Path, wav: Path): Result<Unit> = runCatching {
    val packets = oggPackets(Files.readAllBytes(ogg))
    val head = packets.firstOrNull()
    require(head != null && head.size >= 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an Ogg Opus file" }
    val preSkip = ((head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)) * RATE / 48_000
    val decoder = OpusDecoder(RATE, 1)
    val pcm = ShortArray(MAX_FRAME)
    val out = ByteArrayOutputStream()
    var skip = preSkip
    for (packet in packets.drop(2)) {   // OpusHead, then OpusTags
        val n = decoder.decode(packet, 0, packet.size, pcm, 0, MAX_FRAME, false)
        for (i in 0 until n) {
            if (skip > 0) { skip--; continue }
            out.write(pcm[i].toInt() and 0xff); out.write(pcm[i].toInt() shr 8 and 0xff)
        }
    }
    val bytes = out.toByteArray()
    require(bytes.isNotEmpty()) { "no audio in the voice message" }
    val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
    AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, bytes.size / 2L), AudioFileFormat.Type.WAVE, wav.toFile())
    Unit
}

/** The packets of an Ogg stream, joined across lacing values of 255 and across pages. */
private fun oggPackets(d: ByteArray): List<ByteArray> {
    val packets = mutableListOf<ByteArray>()
    val partial = ByteArrayOutputStream()
    var i = 0
    while (i < d.size) {
        require(i + 27 <= d.size && String(d, i, 4, Charsets.US_ASCII) == "OggS") { "not an Ogg file, or cut short" }
        val segments = d[i + 26].toInt() and 0xff
        require(i + 27 + segments <= d.size) { "cut short" }
        var at = i + 27 + segments
        for (s in 0 until segments) {
            val len = d[i + 27 + s].toInt() and 0xff
            require(at + len <= d.size) { "cut short" }
            partial.write(d, at, len)
            at += len
            if (len < 255) { packets += partial.toByteArray(); partial.reset() }
        }
        i = at
    }
    return packets
}
