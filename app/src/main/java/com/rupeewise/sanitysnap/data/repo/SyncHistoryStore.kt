package com.rupeewise.sanitysnap.data.repo

import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.db.SyncHistoryEntity
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.sync.SettlementRecord
import com.rupeewise.sanitysnap.sync.SyncSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import java.util.UUID

object SyncOutcome {
    const val OK = "OK"
    const val FAILED = "FAILED"
    const val DROPPED = "DROPPED"
}

object SyncTransport {
    const val SIMULATED = "SIMULATED"
    const val NEARBY = "NEARBY"
}

/**
 * Local sync history. Stores the rendered [SyncSummary] JSON plus the received event ids, so a past
 * summary reopens exactly as shown even after later events change the projections. Deleting history
 * only touches the sync_history table: never the event log, never balances.
 */
class SyncHistoryStore(private val db: FairShareDatabase) {
    private val dao = db.syncHistoryDao()

    val all: Flow<List<SyncHistoryEntity>> = dao.observeAll()
    fun observe(id: String) = dao.observe(id)

    suspend fun record(
        sessionId: String,
        peerUserId: String?,
        peerUsername: String?,
        peerDeviceId: String?,
        transport: String,
        outcome: String,
        message: String?,
        sent: Int,
        receivedEventIds: List<String>,
        summary: SyncSummary,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            SyncHistoryEntity(
                id, timestamp, sessionId, peerUserId, peerUsername, peerDeviceId, transport, outcome, message,
                sent, receivedEventIds.size, FsJson.encodeToString(receivedEventIds), FsJson.encodeToString(summary),
            )
        )
        return id
    }

    /** Attaches a settlement attempt to the latest run of [sessionId]; a dropped one marks it DROPPED. */
    suspend fun addSettlement(sessionId: String, record: SettlementRecord) {
        val e = dao.latestForSession(sessionId) ?: return
        val list = settlements(e) + record
        dao.upsert(
            e.copy(
                settlementsJson = FsJson.encodeToString(list),
                outcome = if (record.status == SyncOutcome.DROPPED) SyncOutcome.DROPPED else e.outcome,
            )
        )
    }

    suspend fun byId(id: String) = dao.byId(id)
    suspend fun allNow() = dao.all()
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun clear() = dao.clear()

    companion object {
        fun summary(e: SyncHistoryEntity): SyncSummary? = runCatching { FsJson.decodeFromString<SyncSummary>(e.summaryJson) }.getOrNull()
        fun settlements(e: SyncHistoryEntity): List<SettlementRecord> =
            runCatching { FsJson.decodeFromString<List<SettlementRecord>>(e.settlementsJson) }.getOrDefault(emptyList())
        fun receivedIds(e: SyncHistoryEntity): List<String> =
            runCatching { FsJson.decodeFromString<List<String>>(e.receivedEventIds) }.getOrDefault(emptyList())
    }
}
