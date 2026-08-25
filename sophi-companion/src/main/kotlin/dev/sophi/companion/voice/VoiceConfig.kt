package dev.sophi.companion.voice

/**
 * Local binary/model paths shared by speech-to-text and text-to-speech. Only constructed when
 * [dev.sophi.companion.CompanionSettings.sttEnabled] or [dev.sophi.companion.CompanionSettings.ttsEnabled]
 * is true and all four paths exist on disk (auto-installed defaults, or manually configured).
 */
data class VoiceConfig(
    val whisperBinaryPath: String,
    val whisperModelPath: String,
    val piperPythonPath: String,
    val piperVoicePath: String
)
