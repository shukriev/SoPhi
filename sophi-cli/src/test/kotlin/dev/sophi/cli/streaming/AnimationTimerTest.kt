package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AnimationTimerTest : FunSpec({
    test("nextFrame increments frame counter") {
        val timer = AnimationTimer(frameCount = 10)
        timer.nextFrame() shouldBe 0
        timer.nextFrame() shouldBe 1
    }

    test("nextFrame wraps around at frame count") {
        val timer = AnimationTimer(frameCount = 3)
        timer.nextFrame() // 0
        timer.nextFrame() // 1
        timer.nextFrame() // 2
        timer.nextFrame() shouldBe 0 // wraps to 0
    }

    test("reset returns frame to 0") {
        val timer = AnimationTimer(frameCount = 10)
        timer.nextFrame()
        timer.nextFrame()
        timer.reset()
        timer.nextFrame() shouldBe 0
    }
})
