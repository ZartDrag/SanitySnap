package com.rupeewise.sanitysnap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rupeewise.sanitysnap.sync.SyncController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Sync-screen flow with a simulated peer: connect → settle up → peer prompt → accept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncControllerSettleTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settleUpWithSimulatedPeerB() = runBlocking {
        val local = TestDevices.device(ctx)
        val me = local.createProfile("kartik", "Kartik").userId
        val ctrl = SyncController(ctx, local, this)
        val peerB = ctrl.peers.value[0]
        ctrl.createPeerProfile(peerB, "riya").join()
        ctrl.dismissHomeConflictPrompt()

        // first contact, then B adds an expense it paid for (I owe B half)
        connect(ctrl, peerB)
        ctrl.peerAddsExpense(peerB).join()
        ctrl.syncAgain().join()
        assertTrue(ctrl.homeConflictPrompt.value) // raised after every completed sync
        val riya = peerB.repo.getProfile()!!.userId
        val owed = local.currentLedger().netOwed(me, riya)
        assertTrue(owed > 0)

        val job = ctrl.settleUp(null, owed, owed)
        val prompt = withTimeout(10_000) { ctrl.state.first { it.peerPrompt != null } }.peerPrompt
        assertNotNull(prompt)
        assertEquals(owed, prompt!!.terms.amountMinor)
        ctrl.respondAsPeer(true)
        job.join()
        withTimeout(10_000) { while (peerB.db.paymentDao().allActive().isEmpty()) kotlinx.coroutines.delay(20) }

        assertEquals(0L, local.currentLedger().netOwed(me, riya))
        assertEquals(0L, peerB.repo.currentLedger().netOwed(me, riya))
        // every run is in the local history; the settlement is attached to the session's latest run
        val hist = ctrl.history.allNow()
        assertTrue(hist.size >= 2)
        assertTrue(hist.all { it.peerUsername == "riya" && it.transport == "SIMULATED" })
        assertTrue(hist.flatMap { com.rupeewise.sanitysnap.data.repo.SyncHistoryStore.settlements(it) }.any { it.status == "OK" })
        assertNotNull(ctrl.state.value.lastSummary)
        ctrl.disconnect().join()
        ctrl.resetPeers().join()
    }

    private suspend fun connect(ctrl: SyncController, peer: com.rupeewise.sanitysnap.sync.SimulatedPeer) = kotlinx.coroutines.coroutineScope {
        val job = ctrl.syncWithPeer(peer)
        withTimeout(10_000) { ctrl.state.first { it.pendingConfirm != null } }
        ctrl.respondToConfirm(true)
        job.join()
        assertTrue(ctrl.state.value.session?.connected == true)
    }
}
