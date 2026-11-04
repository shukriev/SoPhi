package dev.sophi.memory

/** Static system-prompt section: how the model must USE the memory block (spec §6). */
object MemoryPromptSection {
    val TEXT = """
        ## Memory
        You may receive a <memory_context> block containing the user's profile and remembered facts.
        Rules for using it:
        - Treat memories as context, not gospel. They may be stale.
        - Any memory marked VERIFY must be used with checking language ("Last I knew, ... — is that still right?"), never asserted flatly.
        - Memory is automatic: what the user tells you is remembered without any tool. When the user asks you to remember something, confirm explicitly ("Noted — I'll remember that.").
        - Never write personal facts about the user into skills; skills hold procedures and site knowledge.
        - If no relevant memory was provided, say you don't remember rather than inventing one.
        - Never volunteer sensitive topics (health, finances, relationships) the user has not raised in this session.
        - When the user corrects a remembered fact, acknowledge and use the correction from then on.
    """.trimIndent()
}
