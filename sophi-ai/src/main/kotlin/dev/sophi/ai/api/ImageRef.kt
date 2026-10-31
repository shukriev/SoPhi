package dev.sophi.ai.api

import java.nio.file.Files
import java.nio.file.Path

val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
const val MAX_IMAGES = 4
const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

/** An image on a user message, by file: providers read and encode it only when they build a request,
 *  so sessions and messages never hold the bytes. */
data class ImageRef(val path: String, val mimeType: String) {
    init { require(mimeType in IMAGE_MIME_TYPES) { "Unsupported image type $mimeType" } }
}

/** The images a request can send, and why each other one is left out. */
internal fun usableImages(images: List<ImageRef>): Pair<List<ImageRef>, List<String>> {
    val kept = mutableListOf<ImageRef>()
    val reasons = mutableListOf<String>()
    for (image in images) {
        val file = Path.of(image.path)
        when {
            !Files.isRegularFile(file) -> reasons += "file missing"
            Files.size(file) > MAX_IMAGE_BYTES -> reasons += "over 5 MB"
            kept.size == MAX_IMAGES -> reasons += "more than 4 images"
            else -> kept += image
        }
    }
    return kept to reasons
}

internal fun withLeftOutNotes(content: String, reasons: List<String>): String =
    content + reasons.joinToString("") { "\n[image left out: $it]" }
