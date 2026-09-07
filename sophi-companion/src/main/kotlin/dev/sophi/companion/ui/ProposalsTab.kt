package dev.sophi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sophi.companion.CompanionRuntime
import dev.sophi.schedule.model.Proposal

/**
 * Read + accept/reject only, matching what `sophi proposals list/accept/reject` already does.
 * Deliberately no "Implement"/"Cleanup" here -- ADR-032 scoped `sophi proposals implement` as a
 * heavyweight, CLI-only, human-run action (spawns Claude Code, builds twice, pushes a real
 * branch, opens a real PR). Turning an accepted proposal into a PR still means dropping to the CLI.
 */
@Composable
fun ProposalsTab(runtime: CompanionRuntime) {
    var proposals by remember { mutableStateOf(listOf<Proposal>()) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    var rejectingId by remember { mutableStateOf<String?>(null) }

    fun refresh() { proposals = runtime.proposals() }
    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Proposals", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        if (proposals.isEmpty()) {
            Text("No proposals yet.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(proposals, key = { it.id }) { proposal ->
                ProposalRow(
                    proposal = proposal,
                    expanded = expandedIds.contains(proposal.id),
                    onToggle = {
                        expandedIds = if (expandedIds.contains(proposal.id)) expandedIds - proposal.id else expandedIds + proposal.id
                    },
                    onAccept = { runtime.acceptProposal(proposal.id); refresh() },
                    onReject = { rejectingId = proposal.id }
                )
            }
        }
    }

    rejectingId?.let { id ->
        RejectProposalDialog(
            onConfirm = { reason -> runtime.rejectProposal(id, reason); rejectingId = null; refresh() },
            onDismiss = { rejectingId = null }
        )
    }
}

@Composable
private fun ProposalRow(
    proposal: Proposal,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(proposal.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${proposal.category} · ${proposal.status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (proposal.status == "pending") {
                TextButton(onClick = onAccept) { Text("Accept") }
                TextButton(onClick = onReject) { Text("Reject") }
            }
        }
        CollapsibleCard(
            expanded = expanded,
            onToggle = onToggle,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
            summary = proposal.rationale.take(80) + if (proposal.rationale.length > 80) "…" else "",
            full = "${proposal.rationale}\n\n${proposal.suggestedAction}" +
                (proposal.implementedDetail?.let { "\n\n${it}" } ?: "")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RejectProposalDialog(onConfirm: (reason: String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).width(400.dp)) {
                Text("Reject proposal", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("Reason") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) { Text("Reject") }
                }
            }
        }
    }
}
