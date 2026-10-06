package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LabeledRow
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard
import com.rupeewise.sanitysnap.ui.SettleUpLocked
import com.rupeewise.sanitysnap.ui.nameOf
import kotlinx.coroutines.launch

@Composable
fun GroupDetailScreen(groupId: String, onBack: () -> Unit, addExpense: () -> Unit, editExpense: (String) -> Unit, openSync: () -> Unit = {}) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val group by repo.group(groupId).collectAsStateWithLifecycle(null)
    val members by repo.groupMembers(groupId).collectAsStateWithLifecycle(emptyList())
    val expenses by repo.groupExpenses(groupId).collectAsStateWithLifecycle(emptyList())
    val allExpenses by repo.expenses.collectAsStateWithLifecycle(emptyList())
    val payers by repo.allPayers.collectAsStateWithLifecycle(emptyList())
    val shares by repo.allShares.collectAsStateWithLifecycle(emptyList())
    val payments by repo.payments.collectAsStateWithLifecycle(emptyList())
    val challenges by repo.challenges.collectAsStateWithLifecycle(emptyList())
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val friendships by repo.friendships.collectAsStateWithLifecycle(emptyList())
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val me = profile?.userId
    val byId = users.associateBy { it.id }
    val memberIds = members.map { it.userId }
    val ledger = FairShareRepository.buildLedger(allExpenses, payers, shares, payments, groupId, filterByGroup = true)
    val net = ledger.netPositions()
    val openChallengeByExpense = challenges.filter { it.status == ChallengeStatus.OPEN }.groupBy { it.expenseId }

    var showAddMember by remember { mutableStateOf(false) }
    var challengeFor by remember { mutableStateOf<String?>(null) }

    FsScaffold(title = group?.name ?: "Group", onBack = onBack, snackbar = snackbar) {
        SectionCard("Members") {
            memberIds.forEach { Text(byId.nameOf(it, me)) }
            OutlinedButton(onClick = { showAddMember = true }) { Text("Add member") }
        }
        SectionCard("Group balances") {
            if (net.values.all { it == 0L }) Text("Everyone is settled up")
            memberIds.forEach { uid ->
                val v = net[uid] ?: 0L
                when {
                    v > 0 -> LabeledRow("${byId.nameOf(uid, me)} gets back", Money.format(v), OwedColor)
                    v < 0 -> LabeledRow("${byId.nameOf(uid, me)} owes", Money.format(-v), OweColor)
                }
            }
            Button(onClick = addExpense) { Text("Add expense") }
            // Settling is a synchronous two-phone operation: only possible from a live sync session.
            if (me != null) {
                memberIds.filter { it != me && ledger.netOwed(me, it) != 0L }.forEach { other ->
                    SettleUpLocked(byId.nameOf(other, me), openSync)
                }
            }
            if (openChallengeByExpense.keys.any { id -> expenses.any { it.id == id } }) {
                Text("Settling is blocked while challenges are open.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Expenses", style = MaterialTheme.typography.titleMedium)
        if (expenses.isEmpty()) Text("No expenses yet.")
        expenses.forEach { e ->
            val paidBy = payers.filter { it.expenseId == e.id }.joinToString { byId.nameOf(it.userId, me) }
            SectionCard {
                LabeledRow(e.description, Money.format(e.amountMinor, e.currency))
                Text("Paid by $paidBy • ${e.splitType.lowercase()} split", style = MaterialTheme.typography.bodySmall)
                val myShare = shares.firstOrNull { it.expenseId == e.id && it.userId == me }?.amountMinor
                if (myShare != null) Text("Your share: ${Money.format(myShare, e.currency)}", style = MaterialTheme.typography.bodySmall)
                if (openChallengeByExpense[e.id] != null) {
                    AssistChip(onClick = {}, label = { Text("Challenged (${openChallengeByExpense[e.id]!!.size})") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editExpense(e.id) }) { Text("Edit") }
                    TextButton(onClick = { challengeFor = e.id }) { Text("Challenge") }
                    TextButton(onClick = { scope.launch { repo.deleteExpense(e.id) } }) { Text("Delete") }
                }
            }
        }
        val groupPayments = payments.filter { it.groupId == groupId }
        if (groupPayments.isNotEmpty()) {
            Text("Payments", style = MaterialTheme.typography.titleMedium)
            groupPayments.forEach { p ->
                Text("${byId.nameOf(p.fromUserId, me)} paid ${byId.nameOf(p.toUserId, me)} ${Money.format(p.amountMinor, p.currency)}")
            }
        }
    }

    if (showAddMember) {
        val friendIds = friendships.mapNotNull { f -> when (me) { f.userIdA -> f.userIdB; f.userIdB -> f.userIdA; else -> null } }
            .filter { it !in memberIds }.distinct()
        AlertDialog(
            onDismissRequest = { showAddMember = false },
            title = { Text("Add member") },
            text = {
                Column {
                    if (friendIds.isEmpty()) Text("All your friends are already members. Add friends from the Friends screen.")
                    friendIds.forEach { fid ->
                        TextButton(onClick = { scope.launch { repo.addGroupMember(groupId, fid); showAddMember = false } }) {
                            Text("@${byId[fid]?.username ?: fid.take(8)}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddMember = false }) { Text("Close") } },
        )
    }

    challengeFor?.let { expenseId ->
        ChallengeDialog(onDismiss = { challengeFor = null }) { reason ->
            scope.launch {
                repo.raiseChallenge(expenseId, reason)
                challengeFor = null
                snackbar.showSnackbar("Challenge raised — settling is blocked until it is resolved")
            }
        }
    }
}

@Composable
fun ChallengeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Challenge this expense") },
        text = {
            Column {
                Text("Open challenges block settling until resolved or withdrawn.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(reason, { reason = it }, label = { Text("What looks wrong?") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(reason) }) { Text("Challenge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
