package dev.sophi.ai.api

/** A model request refused because the session's cost budget is spent; [message] says by how much. */
class BudgetExceededException(message: String) : RuntimeException(message)
