package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class DecayTest : FunSpec({
    val cfg = JanesPalaceConfig()
    fun mem(room: Room, salience: Double, reinforcedAt: Long) = Memory(
        id = "mem_x", text = "t", room = room, salience = salience,
        signals = SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
        sensitivity = Sensitivity.PERSONAL, provenance = Provenance.USER_DIRECT,
        createdAt = reinforcedAt, reinforcedAt = reinforcedAt, sourceSessionId = "s"
    )

    test("fresh memory has priority equal to its salience") {
        priority(mem(Room.EPISODES, 0.8, 1_000L), 1_000L, cfg.halfLifeMs) shouldBe (0.8 plusOrMinus 1e-9)
    }

    test("priority halves after exactly one half-life") {
        val hl = cfg.halfLifeMs.getValue(Room.EPISODES)   // 72h
        priority(mem(Room.EPISODES, 0.8, 0L), hl, cfg.halfLifeMs) shouldBe (0.4 plusOrMinus 1e-9)
    }

    test("rooms decay at different rates: episode fades far faster than entity") {
        val week = 7L * 24 * 3600 * 1000
        val episode = priority(mem(Room.EPISODES, 1.0, 0L), week, cfg.halfLifeMs)
        val entity = priority(mem(Room.ENTITIES, 1.0, 0L), week, cfg.halfLifeMs)
        (episode < 0.25) shouldBe true     // 7d ≈ 2.33 half-lives of 72h
        (entity > 0.9) shouldBe true       // 7d against a 90d half-life
    }

    test("config carries spec defaults") {
        cfg.significanceThreshold shouldBe 0.35
        cfg.wAff shouldBe 0.30
        cfg.beta1 shouldBe 0.45
        cfg.mergeThreshold shouldBe 0.92
        cfg.halfLifeMs.getValue(Room.NARRATIVE) shouldBe 365L * 24 * 3600 * 1000
    }
})
