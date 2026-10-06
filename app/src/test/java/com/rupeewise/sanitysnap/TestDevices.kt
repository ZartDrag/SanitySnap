package com.rupeewise.sanitysnap

import android.content.Context
import androidx.room.Room
import com.rupeewise.sanitysnap.crypto.SoftwareSigner
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.SettlementTerms
import com.rupeewise.sanitysnap.sync.InMemoryChannel
import com.rupeewise.sanitysnap.sync.LiveSession
import com.rupeewise.sanitysnap.sync.SettlementOutcome
import com.rupeewise.sanitysnap.sync.SimulatedLink
import com.rupeewise.sanitysnap.sync.SyncEngine
import com.rupeewise.sanitysnap.sync.SyncMode
import com.rupeewise.sanitysnap.sync.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertNull

/** Shared helpers: in-memory "phones" and live sessions over a (droppable) simulated link. */
object TestDevices {
    fun device(ctx: Context): FairShareRepository {
        val db = Room.inMemoryDatabaseBuilder(ctx, FairShareDatabase::class.java).allowMainThreadQueries().build()
        return FairShareRepository(db, SoftwareSigner.generate())
    }

    suspend fun sync(a: FairShareRepository, b: FairShareRepository, modeA: SyncMode = SyncMode.NORMAL): Pair<SyncResult, SyncResult> =
        coroutineScope {
            val (ca, cb) = InMemoryChannel.pair()
            val rb = async { SyncEngine(b).runSession(cb, confirmPeer = { true }) }
            val ra = SyncEngine(a).runSession(ca, modeA, confirmPeer = { true })
            ra to rb.await()
        }

    class Live(val a: LiveSession, val b: LiveSession, val link: SimulatedLink, val bOutcome: CompletableDeferred<SettlementOutcome>) {
        suspend fun close() { a.disconnect(); b.disconnect() }
    }

    /** Full sync (keepOpen) and then a live session on both ends. B's user answers with [acceptOnB]. */
    suspend fun connect(
        scope: CoroutineScope,
        a: FairShareRepository,
        b: FairShareRepository,
        acceptOnB: suspend (SettlementTerms) -> Boolean = { true },
    ): Live {
        val link = SimulatedLink()
        val rb = scope.async { SyncEngine(b).runSession(link.b, confirmPeer = { true }, keepOpen = true) }
        val ra = SyncEngine(a).runSession(link.a, confirmPeer = { true }, keepOpen = true)
        val rbr = rb.await()
        assertNull(ra.aborted); assertNull(rbr.aborted)
        val outcome = CompletableDeferred<SettlementOutcome>()
        val sa = LiveSession(a, link.a, ra.peer!!, scope, confirmIncoming = { false }, stepTimeoutMs = 5_000, promptTimeoutMs = 5_000).start()
        val sb = LiveSession(
            b, link.b, rbr.peer!!, scope, confirmIncoming = acceptOnB,
            onIncomingOutcome = { outcome.complete(it) }, stepTimeoutMs = 5_000, promptTimeoutMs = 5_000,
        ).start()
        return Live(sa, sb, link, outcome)
    }
}
