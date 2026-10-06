package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.SectionCard
import com.rupeewise.sanitysnap.ui.nameOf
import kotlinx.coroutines.launch

/** Challenges raised on expenses. Open challenges block settling in that group / between those people. */
@Composable
fun ChallengeScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val challenges by repo.challenges.collectAsStateWithLifecycle(emptyList())
    val expenses by repo.expenses.collectAsStateWithLifecycle(emptyList())
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val byId = users.associateBy { it.id }
    val expById = expenses.associateBy { it.id }

    FsScaffold(title = "Challenges", onBack = onBack) {
        if (challenges.isEmpty()) Text("No challenges. Challenge an expense from a group or from the post-sync summary.")
        challenges.forEach { c ->
            val e = expById[c.expenseId]
            SectionCard("${e?.description ?: "Deleted expense"} • ${c.status}") {
                if (e != null) Text(Money.format(e.amountMinor, e.currency))
                Text("Raised by ${byId.nameOf(c.raisedBy, profile?.userId)}: \"${c.reason}\"")
                c.resolutionNote?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                if (c.status == ChallengeStatus.OPEN) {
                    var note by remember(c.id) { mutableStateOf("") }
                    OutlinedTextField(note, { note = it }, label = { Text("Resolution note (optional)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { repo.setChallengeStatus(c.id, ChallengeStatus.RESOLVED, note.ifBlank { null }) } }) { Text("Mark resolved") }
                        TextButton(onClick = { scope.launch { repo.setChallengeStatus(c.id, ChallengeStatus.WITHDRAWN, note.ifBlank { null }) } }) { Text("Withdraw") }
                    }
                }
            }
        }
    }
}
