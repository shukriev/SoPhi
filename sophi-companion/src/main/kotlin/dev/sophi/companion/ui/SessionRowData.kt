package dev.sophi.companion.ui

import dev.sophi.companion.SessionState

data class SessionRowData(
    val id: String,
    val title: String,
    val isRemote: Boolean,
    val state: SessionState,
    val lastActiveMillis: Long
)

private fun SessionState.urgencyRank(): Int = when (this) {
    is SessionState.Error -> 0
    is SessionState.NeedsConfirmation -> 0
    SessionState.Running -> 1
    SessionState.Idle -> 2
}

/** Rows needing attention (Error, NeedsConfirmation) first, then Running, then Idle; most recently active first within each group. */
fun sortForSidebar(rows: List<SessionRowData>): List<SessionRowData> =
    rows.sortedWith(
        compareBy<SessionRowData> { it.state.urgencyRank() }
            .thenByDescending { it.lastActiveMillis }
            .thenBy { it.title }
    )
