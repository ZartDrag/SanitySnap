package com.rupeewise.sanitysnap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rupeewise.sanitysnap.TestDevices.connect
import com.rupeewise.sanitysnap.TestDevices.device
import com.rupeewise.sanitysnap.TestDevices.sync
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.DiffOp
import com.rupeewise.sanitysnap.domain.DiffSegment
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.sync.ChangeSummary
import com.rupeewise.sanitysnap.sync.SettlementOutcome
import com.rupeewise.sanitysnap.sync.SummaryItem
import com.rupeewise.sanitysnap.sync.SummaryKind
import com.rupeewise.sanitysnap.sync.SyncSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Post-sync summary: every change type, grouping, relayed vs direct, balances, conflicts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncSummaryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Sync [me] with [peer] and return the summary [me] would show. */
    private suspend fun summarySync(me: FairShareRepository, peer: FairShareRepository): SyncSummary {
        val before = ChangeSummary.capture(me)
        val (r, _) = sync(me, peer)
        return ChangeSummary.build(me, before, r.newEvents, r.peer!!.deviceId, "@" + r.peer!!.username)
    }

    private fun SyncSummary.items() = sections.flatMap { it.items }
    private fun SyncSummary.only(kind: String): SummaryItem = items().single { it.kind == kind }

    @Test
    fun summaryCoversEveryChangeType() = runBlocking {
        val a = device(ctx); val b = device(ctx); val c = device(ctx)
        val ua = a.createProfile("kartik", "Kartik").userId
        val ub = b.createProfile("riya", "Riya").userId
        val uc = c.createProfile("arjun", "Arjun").userId

        // 1. first contact: new person + friendship, directly from the peer
        var s = summarySync(a, b)
        assertTrue(s.items().any { it.kind == SummaryKind.PERSON && it.title.contains("@riya") && !it.relayed })
        assertTrue(s.items().any { it.kind == SummaryKind.FRIEND })

        // 2. new group + new expense, grouped under the group, my share highlighted, balance change
        val g = b.createGroup("Goa", listOf(ua))
        val exp = (b.addExpense(g, "Dinner", 90_000, SplitType.EQUAL, mapOf(ub to 90_000L), listOf(ShareInput(ua), ShareInput(ub))) as ActionResult.Ok).value
        s = summarySync(a, b)
        val goa = s.sections.single { it.title == "Group \u201CGoa\u201D" }
        assertTrue(goa.items.any { it.kind == SummaryKind.GROUP })
        val newExp = s.only(SummaryKind.EXPENSE_NEW)
        assertEquals(exp, newExp.expenseId)
        assertTrue(newExp.title.contains("Dinner") && newExp.title.contains("₹900.00"))
        assertTrue(newExp.details.contains("Your share: ₹450.00"))
        assertTrue(newExp.details.any { it.startsWith("Paid by @riya") })
        assertTrue(newExp.split.single { it.isMe }.amount == "₹450.00")
        assertTrue(newExp.origin.startsWith("@riya on device ${b.signer.deviceId.take(6)}"))
        val bal = s.balanceChanges.single()
        assertEquals("@riya", bal.friend); assertEquals(0L, bal.beforeMinor); assertEquals(-45_000L, bal.afterMinor)

        // 3. C meets B, B adds C to the group, C challenges the expense → reaches A *relayed* via B
        sync(b, c)
        b.addGroupMember(g, uc)
        sync(b, c)
        val ch = (c.raiseChallenge(exp, "Was it really 900?") as ActionResult.Ok).value
        sync(c, b)
        s = summarySync(a, b)
        assertFalse(s.only(SummaryKind.MEMBER).relayed)
        val raised = s.only(SummaryKind.CHALLENGE_RAISED)
        assertTrue(raised.relayed)
        assertTrue(raised.origin.contains("@arjun") && raised.origin.contains("relayed by @riya"))
        assertTrue(s.items().any { it.kind == SummaryKind.PERSON && it.title.contains("@arjun") && it.relayed })
        assertTrue(s.relayedCount > 0 && s.directCount > 0)

        // 4. challenge withdrawn (relayed)
        c.setChallengeStatus(ch, ChallengeStatus.WITHDRAWN, "ok")
        sync(c, b)
        s = summarySync(a, b)
        assertTrue(s.only(SummaryKind.CHALLENGE_CLOSED).title.contains("withdrew"))

        // 5. edited expense: field-level diff with inline highlight + amount delta + only changed people
        b.addExpense(g, "Dinner at Goa", 100_000, SplitType.EQUAL, mapOf(ub to 100_000L), listOf(ShareInput(ua), ShareInput(ub)), existingId = exp)
        s = summarySync(a, b)
        val edit = s.only(SummaryKind.EXPENSE_EDIT)
        val desc = edit.changes.single { it.label == "Description" }
        assertEquals(listOf(DiffSegment("Dinner", DiffOp.SAME), DiffSegment(" at Goa", DiffOp.ADDED)), desc.segments)
        assertEquals("+₹100.00", edit.changes.single { it.label == "Amount" }.delta)
        assertEquals(2, edit.changes.single { it.label == "Split" }.people.size)
        assertEquals(-50_000L, s.balanceChanges.single().afterMinor)

        // 6. settlement (co-signed); a third device sees it relayed via B
        val live = connect(this, a, b)
        assertTrue(live.a.proposeSettlement(g, ua, ub, 50_000) is SettlementOutcome.Settled)
        withTimeout(5_000) { live.bOutcome.await() }
        live.close()
        s = summarySync(c, b)
        val pay = s.only(SummaryKind.PAYMENT)
        assertTrue(pay.relayed && pay.title.contains("₹500.00"))

        // 7. conflict created → listed with its id
        a.updateExpenseSnapshot(a.expenseSnapshot(exp)!!.copy(description = "Dinner (A)"))
        b.updateExpenseSnapshot(b.expenseSnapshot(exp)!!.copy(description = "Dinner (B)"))
        s = summarySync(a, b)
        assertEquals(a.conflicts.first().single().id, s.newConflicts.single().conflictId)

        // 8. deleted expense
        a.resolveConflict(s.newConflicts.single().conflictId, keepOurs = true)
        sync(a, b)
        b.deleteExpense(exp)
        s = summarySync(a, b)
        assertTrue(s.only(SummaryKind.EXPENSE_DELETE).title.contains("deleted"))

        // 9. nothing new
        assertTrue(summarySync(a, b).upToDate)
    }
}
