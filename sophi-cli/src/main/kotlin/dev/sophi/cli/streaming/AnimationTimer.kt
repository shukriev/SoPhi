package dev.sophi.cli.streaming

class AnimationTimer(private val frameCount: Int = 10) {
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
