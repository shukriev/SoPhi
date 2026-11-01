package dev.sophi.ai.api

import java.nio.file.Files
import java.nio.file.Path

const val MAX_IMAGES = 4
/** 5 MB once base64-encoded, the limit Anthropic checks; raw bytes grow by 4/3. */
const val MAX_IMAGE_BYTES = 5L * 1024 * 1024 * 3 / 4

/** An image on a user message, by file: providers read it only when they build a request, so sessions and
 *  messages never hold the bytes. Its type comes from the bytes, never from a name or a label. */
data class ImageRef(val path: String)

/** An image read for one request. */
internal class LoadedImage(val mimeType: String, val bytes: ByteArray)

/** The type the file's first bytes say, or null for anything that isn't a PNG, JPEG, GIF or WebP. */
internal fun sniffImageType(b: ByteArray): String? {
    val h = String(b, 0, minOf(b.size, 12), Charsets.ISO_8859_1)
    return when {
        h.startsWith("\u0089PNG") -> "image/png"
        h.startsWith("ÿØÿ") -> "image/jpeg"
        h.startsWith("GIF8") -> "image/gif"
        h.startsWith("RIFF") && h.startsWith("WEBP", 8) -> "image/webp"
        else -> null
    }
}

/**
 * Reads what a request can send, and says why each other image is left out. Never throws: an image a
 * provider would refuse stays saved on the session and would fail every later turn, so it is left out
 * here instead, with a note the model sees.
 */
internal fun loadImages(images: List<ImageRef>): Pair<List<LoadedImage>, List<String>> {
    val kept = mutableListOf<LoadedImage>()
    val reasons = mutableListOf<String>()
    for (image in images) {
        val file = Path.of(image.path)
        val reason = when {
            !Files.isRegularFile(file) -> "file missing"
            runCatching { Files.size(file) }.getOrDefault(0L) > MAX_IMAGE_BYTES -> "over 5 MB"
            else -> {
                val bytes = runCatching { Files.readAllBytes(file) }.getOrNull()
                val type = bytes?.let(::sniffImageType)
                when {
                    bytes == null -> "can't read the file"
                    type == null -> "not an image"
                    else -> { kept += LoadedImage(type, bytes); null }
                }
            }
        }
        reason?.let { reasons += it }
    }
    return kept to reasons
}

internal fun withLeftOutNotes(content: String, reasons: List<String>): String =
    content + reasons.joinToString("") { "\n[image left out: $it]" }

/**
 * What one request may carry. A model that can't see gets a note for each image instead (so it knows one
 * was there); one that can gets only the newest [max] images of the whole request, older ones as notes,
 * which keeps a long chat with photos under the providers' request size limits.
 */
fun prepareForModel(messages: List<Message>, canSee: Boolean, max: Int = MAX_IMAGES): List<Message> {
    if (messages.none { it.images.isNotEmpty() }) return messages
    var budget = if (canSee) max else 0
    return messages.asReversed().map { m ->
        if (m.images.isEmpty()) m else {
            val keep = m.images.takeLast(budget.coerceAtMost(m.images.size))
            budget -= keep.size
            val dropped = m.images.size - keep.size
            val reason = if (canSee) "earlier image" else "this model can't see images"
            m.copy(images = keep, content = withLeftOutNotes(m.content, List(dropped) { reason }))
        }
    }.asReversed()
}
