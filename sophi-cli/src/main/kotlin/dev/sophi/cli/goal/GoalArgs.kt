package dev.sophi.cli.goal

data class GoalArgs(val task: String, val check: String?) {
    companion object {
        const val USAGE = "Usage: /goal [--check \"<command>\"] <task>"
        private val CHECK_PREFIX = Regex("^--check\\s+")

        fun parse(raw: String?): Result<GoalArgs> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException(USAGE))

            val match = CHECK_PREFIX.find(trimmed)
                ?: return Result.success(GoalArgs(task = trimmed, check = null))

            val afterFlag = trimmed.substring(match.value.length)
            if (afterFlag.isEmpty()) return Result.failure(IllegalArgumentException(USAGE))

            val (check, rest) = if (afterFlag.startsWith("\"")) {
                val end = afterFlag.indexOf('"', 1)
                if (end < 0) return Result.failure(IllegalArgumentException(USAGE))
                afterFlag.substring(1, end) to afterFlag.substring(end + 1).trimStart()
            } else {
                val sp = afterFlag.indexOfFirst { it.isWhitespace() }
                if (sp < 0) return Result.failure(IllegalArgumentException(USAGE))
                afterFlag.substring(0, sp) to afterFlag.substring(sp).trimStart()
            }

            if (rest.isEmpty()) return Result.failure(IllegalArgumentException(USAGE))
            return Result.success(GoalArgs(task = rest, check = check))
        }
    }
}
