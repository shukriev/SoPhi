package dev.sophi.core.session

import dev.sophi.ai.api.ImageRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class StoredImage(val path: String, val mimeType: String)

private val imagesJson = Json { ignoreUnknownKeys = true }

/** A USER entry's images, as metadata "images": file references only, never bytes. Empty = no key. */
fun imagesMetadata(images: List<ImageRef>): Map<String, String> =
    if (images.isEmpty()) emptyMap()
    else mapOf("images" to imagesJson.encodeToString(ListSerializer(StoredImage.serializer()), images.map { StoredImage(it.path, it.mimeType) }))

/** The images saved on this entry; unreadable metadata reads as none rather than failing a turn. */
fun SessionEntry.images(): List<ImageRef> = metadata["images"]?.let { raw ->
    runCatching {
        imagesJson.decodeFromString(ListSerializer(StoredImage.serializer()), raw).mapNotNull { runCatching { ImageRef(it.path, it.mimeType) }.getOrNull() }
    }.getOrNull()
}.orEmpty()
