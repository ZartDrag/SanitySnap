package com.rupeewise.sanitysnap

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rupeewise.sanitysnap.crypto.SoftwareSigner
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.sync.BackupManager
import com.rupeewise.sanitysnap.sync.EventLog
import com.rupeewise.sanitysnap.sync.InMemoryChannel
import com.rupeewise.sanitysnap.sync.SettlementOutcome
import com.rupeewise.sanitysnap.sync.SyncEngine
import com.rupeewise.sanitysnap.sync.SyncMode
import com.rupeewise.sanitysnap.sync.SyncResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end: real Room DBs + real signing + real sync protocol over in-memory channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncIntegrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun device(): FairShareRepository {
        val db = Room.inMemoryDatabaseBuilder(ctx, FairShareDatabase::class.java).allowMainThreadQueries().build()
        return FairShareRepository(db, SoftwareSigner.generate())
    }

    private suspend fun sync(a: FairShareRepository, b: FairShareRepository, modeA: SyncMode = SyncMode.NORMAL): Pair<SyncResult, SyncResult> =
        coroutineScope {
            val (ca, cb) = InMemoryChannel.pair()
            val rb = async { SyncEngine(b).runSession(cb, confirmPeer = { true }) }
            val ra = SyncEngine(a).runSession(ca, modeA, confirmPeer = { true })
            ra to rb.await()
        }

    private suspend fun events(r: FairShareRepository) = r.db.eventDao().allOrdered()

    @Test
    fun fullFlow() = runBlocking {
        val a = device(); val b = device(); val c = device()
        val ua = a.createProfile("kartik", "Kartik").userId
        val ub = b.createProfile("riya", "Riya").userId
        c.createProfile("arjun", "Arjun")

        // First contact: friendship + users exchanged
        val (ra, rb) = sync(a, b)
        assertNull(ra.aborted); assertNull(rb.aborted)
        assertEquals("riya", a.db.userDao().byId(ub)?.username)
        assertEquals(1, a.friendships.first().size)

        // Expense on A, equal split, sync to B
        val g = a.createGroup("Goa", listOf(ub))
        val r = a.addExpense(g, "Dinner", 90_000, SplitType.EQUAL, mapOf(ua to 90_000L), listOf(ShareInput(ua), ShareInput(ub)))
        assertTrue(r is ActionResult.Ok)
        val expId = (r as ActionResult.Ok).value
        sync(a, b)
        assertEquals(45_000L, b.currentLedger().netOwed(ub, ua))
        assertEquals(45_000L, a.currentLedger().netOwed(ub, ua))

        // Transitive gossip: C never met A but receives A's events via B
        sync(b, c)
        assertEquals(events(b).map { it.id }.toSet(), events(c).map { it.id }.toSet())
        assertEquals("Dinner", c.db.expenseDao().byId(expId)?.description)

        // Divergent edit -> git-style conflict detected identically on both sides
        a.updateExpenseSnapshot(a.expenseSnapshot(expId)!!.copy(description = "Dinner (A)"))
        b.updateExpenseSnapshot(b.expenseSnapshot(expId)!!.copy(description = "Dinner (B)"))
        sync(a, b)
        val ca = a.conflicts.first(); val cb = b.conflicts.first()
        assertEquals(1, ca.size); assertEquals(1, cb.size)
        assertEquals(ca[0].id, cb[0].id)
        assertEquals("OPEN", ca[0].status)
        // provisional LWW state is identical on both devices
        assertEquals(a.db.expenseDao().byId(expId)?.description, b.db.expenseDao().byId(expId)?.description)

        a.resolveConflict(ca[0].id, keepOurs = true)
        sync(a, b)
        assertEquals("RESOLVED", b.conflicts.first()[0].status)
        assertEquals("Dinner (A)", b.db.expenseDao().byId(expId)?.description)
        assertEquals("Dinner (A)", a.db.expenseDao().byId(expId)?.description)

        // Challenge blocks settling until resolved
        val ch = (b.raiseChallenge(expId, "I only had a salad") as ActionResult.Ok).value
        sync(a, b)
        assertTrue(a.settlementBlocker(g, ub, ua)!!.contains("challenge"))
        b.setChallengeStatus(ch, ChallengeStatus.WITHDRAWN, "ok fine")
        // Settling is only possible inside a live session with the friend (two co-signatures)
        val live = TestDevices.connect(this, a, b)
        assertNull(a.settlementBlocker(g, ub, ua))
        assertTrue(live.a.proposeSettlement(g, ub, ua, 45_000) is SettlementOutcome.Settled)
        assertTrue(kotlinx.coroutines.withTimeout(5_000) { live.bOutcome.await() } is SettlementOutcome.Settled)
        live.close()
        assertEquals(0L, b.currentLedger().netOwed(ub, ua))
        assertEquals(0L, a.currentLedger().netOwed(ub, ua))

        // Encrypted backup round-trip onto a fresh install (adopts identity, merges log)
        val bytes = BackupManager(a).export("hunter22".toCharArray())
        val d = device()
        val imp = BackupManager(d).import(bytes, "hunter22".toCharArray())
        assertEquals("kartik", imp.adoptedUsername)
        assertEquals(ua, d.getProfile()?.userId)
        assertEquals(events(a).size, events(d).size)
        assertEquals(0L, d.currentLedger().netOwed(ub, ua))
        val wrong = runCatching { BackupManager(device()).import(bytes, "nope".toCharArray()) }
        assertTrue(wrong.isFailure)

        // Fresh install "sync with my other device" adopts the account
        val e = device()
        val (re, _) = sync(e, a, SyncMode.ADOPT_PEER_IDENTITY)
        assertNull(re.aborted)
        assertEquals(ua, e.getProfile()?.userId)
        assertEquals(events(a).size, events(e).size)
    }

    @Test
    fun tamperedEventIsRejected() = runBlocking {
        val a = device()
        a.createProfile("kartik", "")
        val ev = events(a).first()
        assertTrue(EventLog.verify(ev))
        assertFalse(EventLog.verify(ev.copy(payload = ev.payload.replace("kartik", "mallory"))))
        val forged = ev.copy(publicKey = SoftwareSigner.generate().publicKeyB64)
        assertFalse(EventLog.verify(forged))
    }
}
