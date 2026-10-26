package dev.sophi.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.mordant.rendering.TextColors
import dev.sophi.core.permissions.PermissionRulesException

/** R5: a malformed rules file stops the command. [toStderr] for entry points whose stdout carries a protocol. */
internal inline fun <T> exitOnBadPermissionRules(toStderr: Boolean = false, block: () -> T): T =
    try {
        block()
    } catch (e: PermissionRulesException) {
        val message = "Error: ${e.message}"
        if (toStderr) System.err.println(message) else println(TextColors.red(message))
        throw ProgramResult(1)
    }
