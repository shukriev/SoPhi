package dev.sophi.infra

class BudgetExceededException(used: Int, limit: Int) :
    RuntimeException("Budget exceeded: $used tokens used, limit is $limit")

class BudgetTracker(private val _limit: Int) {
    private var _used: Int = 0

    fun record(tokens: Int) {
        _used += tokens
        if (_used > _limit) throw BudgetExceededException(_used, _limit)
    }

    fun used(): Int = _used
    fun limit(): Int = _limit
    fun reset() { _used = 0 }
}
