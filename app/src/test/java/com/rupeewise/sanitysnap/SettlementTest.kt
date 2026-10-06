package com.rupeewise.sanitysnap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rupeewise.sanitysnap.TestDevices.connect
import com.rupeewise.sanitysnap.TestDevices.device
import com.rupeewise.sanitysnap.TestDevices.sync
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.SettlementTerms
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.sync.SettlementOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchronous two-device settle-up over a live session (real DBs, real signatures). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettlementTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class World(val a: FairShareRepository, val b: FairShareRepository, val c: FairShareRepository, val ua: String, val ub: String, val g: String, val expId: String)

    /** A (kartik) paid 900 dinner split with B (riya) => B owes A 450. C (arjun) knows both via B. */
    private suspend fun world(): World {
        val a = device(ctx); val b = device(ctx); val c = device(ctx)
        val ua = a.createProfile("kartik", "Kartik").userId
        val ub = b.createProfile("riya", "Riya").userId
        c.createProfile("arjun", "Arjun")
        sync(a, b)
        val g = a.createGroup("Goa", listOf(ub))
        val exp = (a.addExpense(g, "Dinner", 90_000, SplitType.EQUAL, mapOf(ua to 90_000L), listOf(ShareInput(ua), ShareInput(ub))) as ActionResult.Ok).value
        sync(a, b)
        sync(b, c)
        assertEquals(45_000L, a.currentLedger().netOwed(ub, ua))
        assertEquals(45_000L, c.currentLedger().netOwed(ub, ua))
        return World(a, b, c, ua, ub, g, exp)
    }

    @Test
    fun twoSidedSettlement_zeroesBalancesOnBothAndOnThirdDeviceAfterGossip() = runBlocking {
        val w = world()
        val live = connect(this, w.a, w.b)
        val out = live.a.proposeSettlement(null, w.ub, w.ua, 45_000)
        assertTrue("got $out", out is SettlementOutcome.Settled)
        assertTrue(withTimeout(5_000) { live.bOutcome.await() } is SettlementOutcome.Settled)
        live.close()

        assertEquals(0L, w.a.currentLedger().netOwed(w.ub, w.ua))
        assertEquals(0L, w.b.currentLedger().netOwed(w.ub, w.ua))
        val payA = w.a.db.paymentDao().allActive().single()
        assertEquals(payA, w.b.db.paymentDao().allActive().single())
        // the same single event, carrying both co-signatures
        val ev = w.a.db.eventDao().byId(w.b.db.eventDao().allOrdered().last { it.entityType == EntityType.PAYMENT }.id)!!
        val proof = FsJson.decodeFromString<PaymentPayload>(ev.payload).settlement!!
        assertEquals(w.a.signer.publicKeyB64, proof.proposerPublicKey)
        assertEquals(w.b.signer.publicKeyB64, proof.accepterPublicKey)

        // third device gets it by gossip from B (never met A) and agrees
        assertEquals(45_000L, w.c.currentLedger().netOwed(w.ub, w.ua))
        sync(w.c, w.b)
        assertEquals(0L, w.c.currentLedger().netOwed(w.ub, w.ua))
        assertEquals(1, w.c.db.paymentDao().allActive().size)
        // and survives a full rebuild
        w.c.projector.rebuild(w.c.signer.deviceId)
        assertEquals(0L, w.c.currentLedger().netOwed(w.ub, w.ua))
    }

    @Test
    fun settlementBlockedByOpenConflict_thenWorksAfterResolve() = runBlocking {
        val w = world()
        w.a.updateExpenseSnapshot(w.a.expenseSnapshot(w.expId)!!.copy(description = "Dinner (A)"))
        w.b.updateExpenseSnapshot(w.b.expenseSnapshot(w.expId)!!.copy(description = "Dinner (B)"))
        val live = connect(this, w.a, w.b)
        assertEquals("OPEN", w.a.conflicts.first().single().status)

        val out = live.a.proposeSettlement(w.g, w.ub, w.ua, 45_000)
        assertTrue("got $out", out is SettlementOutcome.Blocked)
        assertTrue((out as SettlementOutcome.Blocked).reason.contains("conflict"))
        // peer refuses too, from its own state
        assertNotNull(w.b.settlementBlocker(null, w.ub, w.ua))
        assertTrue(w.a.db.paymentDao().allActive().isEmpty())
        assertTrue(w.b.db.paymentDao().allActive().isEmpty())

        w.a.resolveConflict(w.a.conflicts.first().single().id, keepOurs = true)
        val ok = live.a.proposeSettlement(w.g, w.ub, w.ua, 45_000) // resyncs first, so B gets the RESOLVE
        assertTrue("got $ok", ok is SettlementOutcome.Settled)
        assertTrue(withTimeout(5_000) { live.bOutcome.await() } is SettlementOutcome.Settled)
        live.close()
        assertEquals(0L, w.b.currentLedger().netOwed(w.ub, w.ua))
    }

    @Test
    fun bothKeepMine_createsNewConflictBetweenResolveEvents_whichStillBlocks() = runBlocking {
        val w = world()
        w.a.updateExpenseSnapshot(w.a.expenseSnapshot(w.expId)!!.copy(description = "Dinner (A)"))
        w.b.updateExpenseSnapshot(w.b.expenseSnapshot(w.expId)!!.copy(description = "Dinner (B)"))
        sync(w.a, w.b)
        w.a.resolveConflict(w.a.conflicts.first().single().id, keepOurs = true)
        w.b.resolveConflict(w.b.conflicts.first().single().id, keepOurs = true)
        sync(w.a, w.b)
        val open = w.a.conflicts.first().filter { it.status == "OPEN" }
        assertEquals(1, open.size)
        assertEquals(Op.RESOLVE, w.a.db.eventDao().byId(open[0].oursEventId)!!.op)
        assertEquals(Op.RESOLVE, w.a.db.eventDao().byId(open[0].theirsEventId)!!.op)
        assertNotNull(w.a.settlementBlocker(w.g, w.ub, w.ua))
    }

    @Test
    fun settlementBlockedByOpenChallenge() = runBlocking {
        val w = world()
        w.b.raiseChallenge(w.expId, "I only had a salad")
        val live = connect(this, w.a, w.b)
        val out = live.b.proposeSettlement(null, w.ub, w.ua, 45_000) // B proposes this time
        live.close()
        assertTrue("got $out", out is SettlementOutcome.Blocked)
        assertTrue((out as SettlementOutcome.Blocked).reason.contains("challenge"))
        assertTrue(w.a.db.paymentDao().allActive().isEmpty())
        assertTrue(w.b.db.paymentDao().allActive().isEmpty())
        assertEquals(45_000L, w.a.currentLedger().netOwed(w.ub, w.ua))
    }

    @Test
    fun droppedConnectionMidSettlement_leavesNoSettlementOnEitherSide() = runBlocking {
        val w = world()
        val live = connect(this, w.a, w.b)
        val eventsA = w.a.db.eventDao().allOrdered().size
        live.link.armed = true // dies when A sends SETTLE_FINAL (both co-signatures exist, nothing committed)
        val out = live.a.proposeSettlement(null, w.ub, w.ua, 45_000)
        assertTrue("got $out", out is SettlementOutcome.Dropped)
        assertTrue(withTimeout(5_000) { live.bOutcome.await() } is SettlementOutcome.Dropped)
        assertFalse(live.a.connected.value); assertFalse(live.b.connected.value)
        live.close()

        assertTrue(w.a.db.paymentDao().allActive().isEmpty())
        assertTrue(w.b.db.paymentDao().allActive().isEmpty())
        assertTrue(w.a.db.eventDao().allOrdered().none { it.entityType == EntityType.PAYMENT })
        assertTrue(w.b.db.eventDao().allOrdered().none { it.entityType == EntityType.PAYMENT })
        assertEquals(eventsA, w.a.db.eventDao().allOrdered().size)
        // a later normal sync does not resurrect anything
        sync(w.a, w.b)
        assertEquals(45_000L, w.a.currentLedger().netOwed(w.ub, w.ua))
        assertEquals(45_000L, w.b.currentLedger().netOwed(w.ub, w.ua))
    }

    @Test
    fun declinedOnPeer_recordsNothing() = runBlocking {
        val w = world()
        val live = connect(this, w.a, w.b, acceptOnB = { false })
        val out = live.a.proposeSettlement(null, w.ub, w.ua, 45_000)
        live.close()
        assertTrue("got $out", out is SettlementOutcome.Declined)
        assertTrue(w.a.db.paymentDao().allActive().isEmpty())
        assertTrue(w.b.db.paymentDao().allActive().isEmpty())
    }

    @Test
    fun balanceHashMismatchIsRejected() = runBlocking {
        val w = world()
        val good = w.b.pairBalance(null, w.ub, w.ua)
        val t = SettlementTerms("x", null, w.ub, w.ua, 45_000, balanceHash = good.hash, proposerUserId = w.ua, accepterUserId = w.ub, proposedAt = 1)
        assertEquals(null, w.b.validateSettlementTerms(t))
        assertEquals(good.hash, w.a.pairBalance(null, w.ub, w.ua).hash)
        val bad = w.b.validateSettlementTerms(t.copy(balanceHash = "deadbeef"))
        assertTrue(bad!!.contains("don't match"))
    }

    @Test
    fun oneSidedOrForgedPaymentIsIgnoredByProjection() = runBlocking {
        val w = world()
        // a legacy-style payment written by A alone (no co-signature) must not move balances
        val p = PaymentPayload("p1", null, w.ub, w.ua, 45_000)
        w.a.eventLog.appendLocal(w.ua, EntityType.PAYMENT, "p1", Op.CREATE, FsJson.encodeToString(p))
        w.a.projector.rebuild(w.a.signer.deviceId)
        assertEquals(45_000L, w.a.currentLedger().netOwed(w.ub, w.ua))
        sync(w.a, w.b)
        assertEquals(45_000L, w.b.currentLedger().netOwed(w.ub, w.ua))
        assertTrue(w.b.db.paymentDao().allActive().isEmpty())
    }
}
