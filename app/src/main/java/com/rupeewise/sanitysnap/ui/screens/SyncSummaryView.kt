package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.data.db.ChallengeEntity
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.sync.SettlementRecord
import com.rupeewise.sanitysnap.sync.SummaryItem
import com.rupeewise.sanitysnap.sync.SummaryKind
import com.rupeewise.sanitysnap.sync.SyncSummary
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.diffAnnotated
import com.rupeewise.sanitysnap.ui.valueChangeAnnotated
import kotlinx.coroutines.launch

/**
 * "What exactly changed in this sync", grouped by group / friend. Used by the post-sync bottom
 * sheet and by the Sync history detail screen. Expense items get a Challenge action (or show the
 * challenge status if the expense is already challenged).
 */
@Composable
fun SyncSummaryContent(summary: SyncSummary, settlements: List<SettlementRecord> = emptyList(), onOpenConflicts: () -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val challenges by repo.challenges.collectAsStateWithLifecycle(emptyList())
    val byExpense = challenges.groupBy { it.expenseId }
    var challengeFor by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "From ${summary.peerName}" + (if (summary.peerDevice.isNotEmpty()) " (device ${summary.peerDevice})" else ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary.upToDate && settlements.isEmpty()) {
            Text("Already up to date", style = MaterialTheme.typography.titleMedium)
        } else if (!summary.upToDate) {
            Text(
                "${summary.directCount} change(s) directly from ${summary.peerName}" +
                    if (summary.relayedCount > 0) ", ${summary.relayedCount} relayed through them from other devices" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (summary.balanceChanges.isNotEmpty()) {
            SummaryBlock("Your balance changes") {
                summary.balanceChanges.forEach { b ->
                    val d = b.afterMinor - b.beforeMinor
                    Text(b.text, color = if (d >= 0) OwedColor else OweColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (summary.newConflicts.isNotEmpty()) {
            SummaryBlock("New conflicts") {
                summary.newConflicts.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${c.title} was edited differently on two devices", modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenConflicts) { Text("Open") }
                    }
                }
            }
        }
        if (settlements.isNotEmpty()) {
            SummaryBlock("Settle-up in this session") {
                settlements.forEach { s ->
                    Text(
                        (if (s.status == "OK") "✓ " else "✗ ") + s.text,
                        color = if (s.status == "OK") OwedColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        summary.sections.forEach { section ->
            SummaryBlock(section.title) {
                section.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider()
                    SummaryItemRow(item, byExpense[item.expenseId].orEmpty(), onChallenge = { challengeFor = it })
                }
            }
        }
        toast?.let { Text(it, color = OwedColor, style = MaterialTheme.typography.bodySmall) }
    }

    challengeFor?.let { expenseId ->
        ChallengeDialog(onDismiss = { challengeFor = null }) { reason ->
            scope.launch {
                repo.raiseChallenge(expenseId, reason)
                challengeFor = null
                toast = "Challenge raised — settling is blocked until it is resolved"
            }
        }
    }
}

@Composable
private fun SummaryBlock(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SummaryItemRow(item: SummaryItem, challenges: List<ChallengeEntity>, onChallenge: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KindTag(item.kind)
            if (item.relayed) Tag("Relayed", Color(0xFF8AB4F8))
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        item.details.forEach { d ->
            val mine = d.startsWith("Your share")
            Text(d, style = MaterialTheme.typography.bodySmall, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, color = if (mine) MaterialTheme.colorScheme.primary else Color.Unspecified)
        }
        if (item.split.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    append("Split: ")
                    item.split.forEachIndexed { i, s ->
                        if (i > 0) append(", ")
                        if (s.isMe) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFFC857))) { append("You ${s.amount}") }
                        else append("${s.name} ${s.amount}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item.changes.forEach { ch ->
            Text(ch.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            when {
                ch.segments != null -> Text(diffAnnotated(ch.segments), style = MaterialTheme.typography.bodySmall)
                ch.people.isNotEmpty() -> ch.people.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                else -> Text(valueChangeAnnotated(ch.before, ch.after, ch.delta), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(item.origin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val expenseId = item.expenseId
        if (expenseId != null && item.kind in setOf(SummaryKind.EXPENSE_NEW, SummaryKind.EXPENSE_EDIT, SummaryKind.EXPENSE_RESOLVE)) {
            val open = challenges.filter { it.status == ChallengeStatus.OPEN }
            val latest = challenges.maxByOrNull { it.updatedAt }
            when {
                open.isNotEmpty() -> Tag("Challenged: open (${open.size}) — \u201C${open.first().reason}\u201D", OweColor)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChallenge(expenseId) }) { Text("Challenge") }
                    if (latest != null) Text("Earlier challenge ${latest.status.lowercase()}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun KindTag(kind: String) {
    val (label, color) = when (kind) {
        SummaryKind.EXPENSE_NEW -> "New expense" to OwedColor
        SummaryKind.EXPENSE_EDIT -> "Edited" to Color(0xFFFFC857)
        SummaryKind.EXPENSE_DELETE -> "Deleted" to OweColor
        SummaryKind.EXPENSE_RESOLVE -> "Conflict resolved" to Color(0xFFFFC857)
        SummaryKind.PAYMENT -> "Settlement" to OwedColor
        SummaryKind.PAYMENT_IGNORED -> "Ignored payment" to OweColor
        SummaryKind.PERSON -> "New person" to Color(0xFF8AB4F8)
        SummaryKind.FRIEND -> "Friends" to Color(0xFF8AB4F8)
        SummaryKind.GROUP -> "Group" to Color(0xFF8AB4F8)
        SummaryKind.MEMBER -> "Member" to Color(0xFF8AB4F8)
        SummaryKind.CHALLENGE_RAISED -> "Challenge" to OweColor
        SummaryKind.CHALLENGE_CLOSED -> "Challenge closed" to OwedColor
        else -> "Change" to Color.Gray
    }
    Tag(label, color)
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
