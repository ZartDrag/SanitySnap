package com.rupeewise.sanitysnap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rupeewise.sanitysnap.AppContainer
import com.rupeewise.sanitysnap.data.db.UserEntity

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = fab,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad: PaddingValues ->
        val base = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
        Column(
            modifier = if (scroll) base.verticalScroll(rememberScrollState()) else base,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Text("", modifier = Modifier.padding(bottom = 64.dp))
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun LabeledRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
}

val OwedColor = Color(0xFF1B9E77)
val OweColor = Color(0xFFE4572E)

fun Map<String, UserEntity>.nameOf(id: String, meId: String?): String =
    if (id == meId) "You" else this[id]?.let { "@" + it.username } ?: id.take(8)

/**
 * Settle-up outside a live session: a disabled button plus a hint. Payments can only be recorded
 * from the Sync screen while connected to that friend (both phones co-sign the settlement).
 */
@Composable
fun SettleUpLocked(otherName: String, onOpenSync: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Settle up with $otherName")
        }
        Text(
            "Connect with $otherName to settle up: both phones must be in a live sync session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenSync != null) {
            androidx.compose.material3.TextButton(onClick = onOpenSync) { Text("Open Sync") }
        }
    }
}

/** Modal reminder shown while OPEN conflicts exist (on app open and after every sync). */
@Composable
fun OpenConflictsDialog(count: Int, onResolve: () -> Unit, onLater: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Conflicts need your decision") },
        text = {
            Text(
                if (count == 1) "1 conflict needs a decision. Until it is resolved, settling up with the people involved is blocked."
                else "$count conflicts need a decision. Until they are resolved, settling up with the people involved is blocked.",
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onResolve) { Text("Resolve") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onLater) { Text("Later") } },
    )
}
