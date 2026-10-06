package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.data.db.SyncHistoryEntity
import com.rupeewise.sanitysnap.data.repo.SyncHistoryStore
import com.rupeewise.sanitysnap.data.repo.SyncOutcome
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val histFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale("en", "IN"))

const val HISTORY_DELETE_NOTE =
    "This only removes the sync record from this phone. Your expenses, payments, balances and the signed event log are NOT changed."

/** Past syncs, newest first. Tap to reopen a summary; trash icon or "Clear all" to delete records. */
@Composable
fun SyncHistoryScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val history = LocalContainer.current.sync.history
    val scope = rememberCoroutineScope()
    val entries by history.all.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf<SyncHistoryEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    FsScaffold(
        title = "Sync history", onBack = onBack,
        actions = { if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") } },
    ) {
        if (entries.isEmpty()) Text("No syncs recorded yet. Each sync you run is listed here.")
        entries.forEach { e -> HistoryRow(e, onOpen = { open(e.id) }, onDelete = { confirmDelete = e }) }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this sync record?") },
            text = { Text("Sync with @${e.peerUsername ?: "?"} on ${histFmt.format(Date(e.timestamp))}.\n\n$HISTORY_DELETE_NOTE") },
            confirmButton = { TextButton(onClick = { scope.launch { history.delete(e.id); confirmDelete = null } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all sync history?") },
            text = { Text("Deletes all ${entries.size} sync record(s).\n\n$HISTORY_DELETE_NOTE") },
            confirmButton = { TextButton(onClick = { scope.launch { history.clear(); confirmClear = false } }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(e: SyncHistoryEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val settlements = SyncHistoryStore.settlements(e)
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(2f))) {
                Text("@${e.peerUsername ?: "unknown"} · ${histFmt.format(Date(e.timestamp))}", style = MaterialTheme.typography.titleSmall)
                Text(
                    e.outcome + " · received ${e.receivedCount}, sent ${e.sentCount} · ${e.transport.lowercase()} · device ${e.peerDeviceId?.take(6) ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (e.outcome) { SyncOutcome.OK -> OwedColor; else -> OweColor },
                )
                e.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                settlements.forEach { Text("Settle-up: ${it.status.lowercase()} — ${it.text}", style = MaterialTheme.typography.bodySmall) }
                Text("Tap to view summary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete this record") }
        }
    }
}


/** Reopens a stored summary (rendered at sync time), with live Challenge actions. */
@Composable
fun SyncHistoryDetailScreen(id: String, onBack: () -> Unit, openConflicts: () -> Unit) {
    val history = LocalContainer.current.sync.history
    val entry by history.observe(id).collectAsStateWithLifecycle(null)
    val e = entry
    FsScaffold(title = "Sync summary", onBack = onBack) {
        if (e == null) {
            Text("This record was deleted.")
            return@FsScaffold
        }
        Text("${histFmt.format(Date(e.timestamp))} · ${e.outcome} · received ${e.receivedCount}, sent ${e.sentCount}", style = MaterialTheme.typography.bodySmall)
        e.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val s = SyncHistoryStore.summary(e)
        if (s == null) Text("Summary could not be read.") else SyncSummaryContent(s, SyncHistoryStore.settlements(e), onOpenConflicts = openConflicts)
    }
}
