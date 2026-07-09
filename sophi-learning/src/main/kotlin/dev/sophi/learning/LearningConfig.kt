package dev.sophi.learning

import java.nio.file.Path

data class LearningConfig(
    val home: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning"),
    val scope: String = System.getProperty("user.dir"),
    val reliabilityMinAttempts: Int = 5,
    val reliabilityFailureRate: Double = 0.5,
    val recentWindow: Int = 5000
)
