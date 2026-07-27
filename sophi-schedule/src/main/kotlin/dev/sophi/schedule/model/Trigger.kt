package dev.sophi.schedule.model

import kotlinx.serialization.Serializable

@Serializable
sealed class Trigger {
    @Serializable
    data class Interval(val everySeconds: Long) : Trigger()

    @Serializable
    data class Once(val atMs: Long) : Trigger()

    @Serializable
    data class Cron(val expression: String) : Trigger()

    @Serializable
    object Manual : Trigger()
}
