package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.domain.Ledger
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LabeledRow
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.launch

@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val friendships by repo.friendships.collectAsStateWithLifecycle(emptyList())
    val ledger by repo.ledger.collectAsStateWithLifecycle(Ledger())
    var showAdd by remember { mutableStateOf(false) }
    val me = profile?.userId
    val byId = users.associateBy { it.id }
    val friendIds = friendships.mapNotNull { f ->
        when (me) { f.userIdA -> f.userIdB; f.userIdB -> f.userIdA; else -> null }
    }.distinct()

    FsScaffold(
        title = "Friends", onBack = onBack, snackbar = snackbar,
        fab = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add friend") } },
    ) {
        if (friendIds.isEmpty()) Text("No friends yet. Add one by username, or sync with a nearby phone.")
        friendIds.forEach { fid ->
            val u = byId[fid]
            val owed = if (me != null) ledger.netOwed(fid, me) else 0L // positive: friend owes me
            SectionCard {
                Text("@${u?.username ?: fid.take(8)}", style = MaterialTheme.typography.titleMedium)
                if (u?.placeholder == true) Text("Unverified — added manually; will be verified on first P2P sync", style = MaterialTheme.typography.bodySmall)
                when {
                    owed > 0 -> LabeledRow("owes you", Money.format(owed), OwedColor)
                    owed < 0 -> LabeledRow("you owe", Money.format(-owed), OweColor)
                    else -> Text("settled up", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showAdd) {
        var username by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add friend") },
            text = {
                // TODO(qr): scan friend's QR (username + public key) instead of manual typing.
                OutlinedTextField(username, { username = it.trim() }, label = { Text("Their username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val r = repo.addFriendByUsername(username)) {
                            is ActionResult.Ok -> { showAdd = false; snackbar.showSnackbar("Added @$username") }
                            is ActionResult.Rejected -> snackbar.showSnackbar(r.reason)
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
