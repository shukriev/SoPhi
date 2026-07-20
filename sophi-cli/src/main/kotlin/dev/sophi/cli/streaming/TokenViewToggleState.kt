package dev.sophi.cli.streaming

data class TokenViewToggleState(val isViewingTokens: Boolean = false) {
    fun toggle(): TokenViewToggleState = copy(isViewingTokens = !isViewingTokens)
}
