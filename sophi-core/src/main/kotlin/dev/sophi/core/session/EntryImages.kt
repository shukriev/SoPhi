package dev.sophi.core.session

import dev.sophi.ai.api.ImageRef
import kotlinx.serialization.json.Json

/** A USER entry's images, as metadata "images": a JSON list of file paths, never bytes. Empty = no key. */
fun imagesMetadata(images: List<ImageRef>): Map<String, String> =
    if (images.isEmpty()) emptyMap() else mapOf("images" to Json.encodeToString(images.map { it.path }))

/** The images saved on this entry; unreadable metadata reads as none rather than failing a turn. */
fun SessionEntry.images(): List<ImageRef> =
    metadata["images"]?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty().map(::ImageRef)
