package dev.sophi.cli.streaming

class AnimationTimer(frameCount: Int = 10) {
    private val frameCount = frameCount.coerceAtLeast(1)
    private var currentFrame = 0

    fun nextFrame(): Int {
        val frame = currentFrame
        currentFrame = (currentFrame + 1) % frameCount
        return frame
    }

    fun reset() {
        currentFrame = 0
    }
}
