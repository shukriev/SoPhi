package dev.sophi.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
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
