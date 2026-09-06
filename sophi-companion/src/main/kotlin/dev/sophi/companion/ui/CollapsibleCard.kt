package dev.sophi.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * A tap-to-expand row: collapsed shows a one-line summary, expanded also shows the full text
 * below it. Used for turn entries that can get long (reasoning, tool call args/results) and
 * would otherwise crowd out the actual conversation in ChatTab.
 *
 * Wrapped in DisableSelection: ChatTab's SelectionContainer intercepts the tap gesture before it
 * reaches this card's clickable, which otherwise breaks tap-to-expand entirely. The tradeoff is
 * that reasoning/tool-call text itself isn't selectable — only the user/assistant conversation is.
 */
@Composable
fun CollapsibleCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    container: Color,
    onContainer: Color,
    summary: String,
    full: String,
    modifier: Modifier = Modifier,
) {
    DisableSelection {
        // No vertical padding here — ChatTab's LazyColumn already applies uniform spacing
        // between every row (message, card, or otherwise) via Arrangement.spacedBy, so adding
        // more here would make the gap around a card larger than the gap between two messages.
        Card(
            modifier = modifier.fillMaxWidth().clickable(onClick = onToggle),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    (if (expanded) "▾ " else "▸ ") + summary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expanded) {
                    Text(
                        full,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
