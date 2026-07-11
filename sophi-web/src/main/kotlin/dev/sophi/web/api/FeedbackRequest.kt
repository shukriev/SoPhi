package dev.sophi.web.api

data class FeedbackRequest(
    val entryIndex: Int? = null,
    val polarity: String,
    val reason: String? = null
)
