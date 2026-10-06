package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.Ledger
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LabeledRow
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OpenConflictsDialog
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard

@Composable
fun HomeScreen(navigate: (String) -> Unit) {
    val container = LocalContainer.current
    val repo = container.repo
    val conflictPrompt by container.sync.homeConflictPrompt.collectAsStateWithLifecycle()
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val ledger by repo.ledger.collectAsStateWithLifecycle(Ledger())
    val openConflicts by repo.openConflictCount.collectAsStateWithLifecycle(0)
    val challenges by repo.challenges.collectAsStateWithLifecycle(emptyList())
    val events by repo.eventCount.collectAsStateWithLifecycle(0)
    val me = profile?.userId
    val net = me?.let { ledger.netPositions()[it] } ?: 0L
    val openChallenges = challenges.count { it.status == ChallengeStatus.OPEN }

    FsScaffold(title = "SanitySnap") {
        SectionCard("Hi @${profile?.username ?: ""}") {
            when {
                net > 0 -> LabeledRow("Overall, you are owed", Money.format(net), OwedColor)
                net < 0 -> LabeledRow("Overall, you owe", Money.format(-net), OweColor)
                else -> Text("You're all settled up")
            }
            Text("Device ${profile?.deviceId ?: ""} • $events event(s) in local log", style = MaterialTheme.typography.bodySmall)
        }
        if (openConflicts > 0 || openChallenges > 0) {
            SectionCard("Needs attention") {
                if (openConflicts > 0) Button(onClick = { navigate("conflicts") }, modifier = Modifier.fillMaxWidth()) { Text("$openConflicts sync conflict(s) to resolve") }
                if (openChallenges > 0) Button(onClick = { navigate("challenges") }, modifier = Modifier.fillMaxWidth()) { Text("$openChallenges open challenge(s) — settling blocked") }
            }
        }
        val items = listOf(
            "Friends" to "friends", "Groups" to "groups",
            "Add expense" to "addExpense", "Balances" to "balances",
            "Sync now" to "sync", "Conflicts" to "conflicts",
            "Challenges" to "challenges", "Backup" to "backup",
            "Sync history" to "history",
        )
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, route) ->
                    FilledTonalButton(onClick = { navigate(route) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }

    // Keeps popping up (app open + after every sync) until every conflict is resolved.
    if (conflictPrompt && openConflicts > 0) {
        OpenConflictsDialog(
            openConflicts,
            onResolve = { container.sync.dismissHomeConflictPrompt(); navigate("conflicts") },
            onLater = { container.sync.dismissHomeConflictPrompt() },
        )
    }
}
