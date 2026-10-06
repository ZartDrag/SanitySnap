package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.launch

@Composable
fun GroupsScreen(onBack: () -> Unit, openGroup: (String) -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsStateWithLifecycle(emptyList())
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val friendships by repo.friendships.collectAsStateWithLifecycle(emptyList())
    var showCreate by remember { mutableStateOf(false) }
    val me = profile?.userId
    val friendIds = friendships.mapNotNull { f -> when (me) { f.userIdA -> f.userIdB; f.userIdB -> f.userIdA; else -> null } }.distinct()
    val byId = users.associateBy { it.id }

    FsScaffold(
        title = "Groups", onBack = onBack,
        fab = { FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "Create group") } },
    ) {
        if (groups.isEmpty()) Text("No groups yet. Tap + to create one.")
        groups.forEach { g ->
            Column(Modifier.fillMaxWidth().clickable { openGroup(g.id) }) {
                SectionCard {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text("Currency ${g.currency}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        val selected = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New group") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Members", style = MaterialTheme.typography.labelLarge)
                    if (friendIds.isEmpty()) Text("Add friends first to include them (you can add members later).", style = MaterialTheme.typography.bodySmall)
                    friendIds.forEach { fid ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(fid in selected, { if (it) selected.add(fid) else selected.remove(fid) })
                            Text("@${byId[fid]?.username ?: fid.take(8)}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        val id = repo.createGroup(name, selected.toList())
                        showCreate = false
                        openGroup(id)
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
}
