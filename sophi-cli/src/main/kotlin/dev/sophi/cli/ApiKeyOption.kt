package dev.sophi.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.option

/**
 * `--api-key`, falling back to SOPHI_API_KEY so a key needn't sit on the command line, where `ps` shows
 * it to every process on the machine. Deliberately not OPENAI_API_KEY: a real OpenAI key set globally
 * would be sent to whatever --base-url points at (LM Studio, a self-hosted VM).
 */
internal fun ParameterHolder.apiKeyOption() = option(
    "--api-key", envvar = "SOPHI_API_KEY",
    help = "API key; falls back to SOPHI_API_KEY, then ANTHROPIC_API_KEY for --provider claude. Omit for no-auth local servers."
)
