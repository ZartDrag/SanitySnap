package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.domain.Ledger
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LabeledRow
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OweColor
import com.rupeewise.sanitysnap.ui.OwedColor
import com.rupeewise.sanitysnap.ui.SectionCard
import com.rupeewise.sanitysnap.ui.SettleUpLocked
import com.rupeewise.sanitysnap.ui.nameOf

/** Pairwise balances (no debt simplification in V1, by design). */
@Composable
fun BalancesScreen(onBack: () -> Unit, openSync: () -> Unit = {}) {
    val repo = LocalContainer.current.repo
    val ledger by repo.ledger.collectAsStateWithLifecycle(Ledger())
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val users by repo.users.collectAsStateWithLifecycle(emptyList())
    val me = profile?.userId
    val byId = users.associateBy { it.id }

    FsScaffold(title = "Balances", onBack = onBack) {
        if (me == null) return@FsScaffold
        val net = ledger.netPositions()[me] ?: 0L
        SectionCard("Your total") {
            when {
                net > 0 -> LabeledRow("You are owed", Money.format(net), OwedColor)
                net < 0 -> LabeledRow("You owe", Money.format(-net), OweColor)
                else -> Text("All settled up")
            }
        }
        SectionCard("With each person (all groups + non-group)") {
            val others = ledger.counterparties(me).sortedBy { byId[it]?.username ?: it }
            if (others.isEmpty()) Text("No balances yet")
            others.forEach { o ->
                val owesMe = ledger.netOwed(o, me)
                when {
                    owesMe > 0 -> LabeledRow("${byId.nameOf(o, me)} owes you", Money.format(owesMe), OwedColor)
                    owesMe < 0 -> LabeledRow("You owe ${byId.nameOf(o, me)}", Money.format(-owesMe), OweColor)
                    else -> LabeledRow(byId.nameOf(o, me), "settled")
                }
                if (owesMe != 0L) SettleUpLocked(byId.nameOf(o, me), openSync)
            }
        }
        SectionCard("Everyone (as seen by this device)") {
            ledger.allUsers().filter { it != me }.forEach { u ->
                val v = ledger.netPositions()[u] ?: 0L
                if (v != 0L) LabeledRow(byId.nameOf(u, me), (if (v > 0) "+" else "−") + Money.format(kotlin.math.abs(v)))
            }
            Text("Positive = gets back money overall.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
