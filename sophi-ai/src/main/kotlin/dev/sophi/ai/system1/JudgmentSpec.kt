package dev.sophi.ai.system1

import dev.sophi.ai.api.CompletionRequest

/**
 * A System-1 judgment's production prompt and parser, exposed with JSON at the boundary so evaluation
 * and training render and parse exactly as production does (ADR-042 addendum). Each module keeps its
 * own input and output types internal.
 */
interface JudgmentSpec {
    /** A [Judgments] constant. */
    val name: String

    /** The exact production request for [inputJson]; throws on malformed input. */
    fun render(inputJson: String, model: String): CompletionRequest

    /** Canonical output JSON, or null when production would reject [reply]. */
    fun parse(reply: String): String?
}
