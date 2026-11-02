package dev.sophi.gateway

/** How a voice message or a photo reads as chat text, so the model gets it and any host's chat can show it. */
private const val VOICE = "[voice]\n"
private const val PHOTO_TEXT = "\n\nText in the photo:\n"

fun voiceMessage(transcript: String): String = VOICE + transcript

/** The text read in the photo goes after the caption, for models that can't see the picture. */
fun photoMessage(caption: String, text: String): String = caption + PHOTO_TEXT + text

data class Shown(val voice: Boolean, val body: String)

/** What a chat shows for a user message: a photo's caption without its read text; a voice message's words. */
fun parseMessage(text: String, hasImages: Boolean): Shown = when {
    text.startsWith(VOICE) -> Shown(voice = true, body = text.removePrefix(VOICE))
    hasImages && PHOTO_TEXT in text -> Shown(voice = false, body = text.substringBefore(PHOTO_TEXT))
    else -> Shown(voice = false, body = text)
}
