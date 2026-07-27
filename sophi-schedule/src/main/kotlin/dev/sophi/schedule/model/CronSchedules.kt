package dev.sophi.schedule.model

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object CronSchedules {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    /** Returns an error message if [expression] is invalid, or null if it's valid. */
    fun validate(expression: String): String? = runCatching { parser.parse(expression) }
        .fold(onSuccess = { null }, onFailure = { it.message ?: "invalid cron expression" })

    /**
     * Next fire time strictly after [afterMs], in the system's local timezone, as epoch
     * millis — or null if [expression] can never match (e.g. Feb 30).
     */
    fun nextFireTimeAfter(expression: String, afterMs: Long): Long? {
        val cron = parser.parse(expression)
        val after = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMs), ZoneId.systemDefault())
        return ExecutionTime.forCron(cron).nextExecution(after)
            .map { it.toInstant().toEpochMilli() }
            .orElse(null)
    }
}
