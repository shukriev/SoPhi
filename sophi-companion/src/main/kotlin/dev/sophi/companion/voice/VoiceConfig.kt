package dev.sophi.companion.voice

/**
 * Local binary/model paths for voice mode. Only constructed when
 * [dev.sophi.companion.CompanionSettings.voiceEnabled] is true — at that point
 * `validationError()` already guarantees all four path fields are non-blank.
 */
data class VoiceConfig(
    val whisperBinaryPath: String,
    val whisperModelPath: String,
    val piperPythonPath: String,
    val piperVoicePath: String
)
