package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitCalculator
import com.rupeewise.sanitysnap.domain.SplitResult
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.SectionCard
import com.rupeewise.sanitysnap.ui.nameOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Add / edit expense. Supports equal, exact, percent and shares splits with a single payer.
 * (Multiple payers are supported by the data model + sync; the UI exposes one payer in V1.)
 */
@Composable
fun AddExpenseScreen(groupId: String?, expenseId: String?, onDone: () -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val friendships by repo.friendships.collectAsStateWithLifecycle(emptyList())
    val groups by repo.groups.collectAsStateWithLifecycle(emptyList())
    var selectedGroup by remember { mutableStateOf(groupId) }
    val members by remember(selectedGroup) {
        selectedGroup?.let { repo.groupMembers(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val me = profile?.userId
    val byId = users.associateBy { it.id }

    val candidates: List<String> = if (selectedGroup != null) {
        members.map { it.userId }
    } else {
        listOfNotNull(me) + friendships.mapNotNull { f -> when (me) { f.userIdA -> f.userIdB; f.userIdB -> f.userIdA; else -> null } }.distinct()
    }

    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var payer by remember { mutableStateOf<String?>(null) }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    val included = remember { mutableStateMapOf<String, Boolean>() }
    val values = remember { mutableStateMapOf<String, String>() }
    var loaded by remember { mutableStateOf(expenseId == null) }

    LaunchedEffect(expenseId) {
        if (expenseId != null) {
            val snap = repo.expenseSnapshot(expenseId)
            if (snap != null) {
                selectedGroup = snap.groupId
                description = snap.description
                amountText = Money.plain(snap.amountMinor)
                payer = snap.payers.maxByOrNull { it.amountMinor }?.userId
                splitType = runCatching { SplitType.valueOf(snap.splitType) }.getOrDefault(SplitType.EQUAL)
                snap.shares.forEach { s ->
                    included[s.userId] = true
                    values[s.userId] = when (splitType) {
                        SplitType.EXACT -> Money.plain(s.amountMinor)
                        SplitType.PERCENT, SplitType.SHARES -> (s.inputValue ?: 0.0).toString()
                        SplitType.EQUAL -> ""
                    }
                }
            }
            loaded = true
        }
    }

    val effectivePayer = payer ?: me
    val participants = candidates.filter { included[it] ?: true }
    val amountMinor = Money.parseToMinor(amountText)
    val preview = amountMinor?.let { amt ->
        SplitCalculator.compute(amt, splitType, participants.map { ShareInput(it, values[it]?.toDoubleOrNull() ?: 0.0) })
    }

    FsScaffold(title = if (expenseId == null) "Add expense" else "Edit expense", onBack = onDone, snackbar = snackbar) {
        if (!loaded) {
            Text("Loading…")
            return@FsScaffold
        }
        if (expenseId == null) SectionCard("Where") {
            FilterChip(selected = selectedGroup == null, onClick = { selectedGroup = null }, label = { Text("No group (friends)") })
            groups.forEach { g ->
                FilterChip(selected = selectedGroup == g.id, onClick = { selectedGroup = g.id }, label = { Text(g.name) })
            }
        }
        OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            amountText, { amountText = it }, label = { Text("Amount (₹ INR)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        SectionCard("Paid by") {
            candidates.forEach { uid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = effectivePayer == uid, onClick = { payer = uid })
                    Text(byId.nameOf(uid, me))
                }
            }
        }
        SectionCard("Split") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SplitType.entries.forEach { t ->
                    FilterChip(selected = splitType == t, onClick = { splitType = t }, label = { Text(t.name.lowercase()) })
                }
            }
            candidates.forEach { uid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(included[uid] ?: true, { included[uid] = it })
                    Text(byId.nameOf(uid, me), modifier = Modifier.weight(1f))
                    if (splitType != SplitType.EQUAL && (included[uid] ?: true)) {
                        OutlinedTextField(
                            values[uid] ?: "", { values[uid] = it },
                            label = { Text(when (splitType) { SplitType.EXACT -> "₹"; SplitType.PERCENT -> "%"; else -> "shares" }) },
                            singleLine = true, modifier = Modifier.width(110.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
            }
            when (preview) {
                is SplitResult.Ok -> preview.shares.forEach {
                    Text("${byId.nameOf(it.userId, me)} owes ${Money.format(it.amountMinor)}", style = MaterialTheme.typography.bodySmall)
                }
                is SplitResult.Error -> Text(preview.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                null -> {}
            }
        }
        Button(
            enabled = me != null && amountMinor != null && effectivePayer != null,
            onClick = {
                val amt = amountMinor ?: return@Button
                val p = effectivePayer ?: return@Button
                scope.launch {
                    val r = repo.addExpense(
                        groupId = selectedGroup, description = description, amountMinor = amt, splitType = splitType,
                        payers = mapOf(p to amt),
                        participants = participants.map { ShareInput(it, values[it]?.toDoubleOrNull() ?: 0.0) },
                        existingId = expenseId,
                    )
                    when (r) {
                        is ActionResult.Ok -> onDone()
                        is ActionResult.Rejected -> snackbar.showSnackbar(r.reason)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
