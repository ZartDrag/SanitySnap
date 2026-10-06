package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.crypto.DeviceSigner
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.domain.Op
import kotlinx.serialization.Serializable
import java.util.UUID

/** Wire/backup representation of an event (mirror of [EventEntity]). */
@Serializable
data class WireEvent(
    val id: String,
    val deviceId: String,
    val seq: Long,
    val lamport: Long,
    val authorUserId: String,
    val entityType: String,
    val entityId: String,
    val op: String,
    val payload: String,
    val baseEventId: String? = null,
    val createdAt: Long,
    val publicKey: String,
    val signature: String,
)

fun EventEntity.toWire() = WireEvent(id, deviceId, seq, lamport, authorUserId, entityType, entityId, op, payload, baseEventId, createdAt, publicKey, signature)
fun WireEvent.toEntity() = EventEntity(id, deviceId, seq, lamport, authorUserId, entityType, entityId, op, payload, baseEventId, createdAt, publicKey, signature)

/**
 * Append-only signed event log.
 * - Local events get the next per-device seq and a Lamport timestamp > anything seen.
 * - Each event is signed by the device key over [canonicalBytes].
 * - Remote events are verified before insert; duplicates are ignored (idempotent gossip).
 */
class EventLog(private val db: FairShareDatabase, private val signer: DeviceSigner) {

    val deviceId: String get() = signer.deviceId

    /** Must be called inside a DB transaction together with projection so seq stays gap-free. */
    suspend fun appendLocal(
        authorUserId: String,
        entityType: String,
        entityId: String,
        op: String,
        payload: String,
    ): EventEntity {
        val signed = buildLocal(authorUserId, entityType, entityId, op, payload)
        db.eventDao().insert(signed)
        return signed
    }

    /**
     * Builds and signs the next local event WITHOUT inserting it. Used by the two-device
     * settlement, where the event is only committed once the peer has acknowledged it.
     * Commit with [commitBuilt]; if anything else was appended meanwhile the commit is refused.
     */
    suspend fun buildLocal(
        authorUserId: String,
        entityType: String,
        entityId: String,
        op: String,
        payload: String,
    ): EventEntity {
        val dao = db.eventDao()
        val base = if (op in Op.HEAD_TRACKED && op != Op.CREATE) db.headDao().get(entityType, entityId)?.headEventId else null
        val unsigned = EventEntity(
            id = UUID.randomUUID().toString(),
            deviceId = signer.deviceId,
            seq = dao.maxSeq(signer.deviceId) + 1,
            lamport = dao.maxLamport() + 1,
            authorUserId = authorUserId,
            entityType = entityType,
            entityId = entityId,
            op = op,
            payload = payload,
            baseEventId = base,
            createdAt = System.currentTimeMillis(),
            publicKey = signer.publicKeyB64,
            signature = "",
        )
        return unsigned.copy(signature = CryptoUtil.b64(signer.sign(canonicalBytes(unsigned))))
    }

    /** Inserts an event made by [buildLocal]. Call inside a transaction. False if seq is stale. */
    suspend fun commitBuilt(e: EventEntity): Boolean {
        if (e.deviceId != signer.deviceId || !verify(e)) return false
        if (db.eventDao().maxSeq(signer.deviceId) + 1 != e.seq) return false
        return db.eventDao().insert(e) != -1L
    }

    /** Inserts verified remote events, returns the ones that were actually new. */
    suspend fun insertRemote(events: List<EventEntity>): InsertResult {
        val inserted = mutableListOf<EventEntity>()
        var rejected = 0
        events.sortedWith(compareBy({ it.deviceId }, { it.seq })).forEach { e ->
            if (!verify(e)) {
                rejected++
                return@forEach
            }
            if (db.eventDao().insert(e) != -1L) inserted += e
        }
        return InsertResult(inserted, rejected)
    }

    data class InsertResult(val inserted: List<EventEntity>, val rejected: Int)

    companion object {
        /** Length-prefixed field encoding so no separator injection is possible. */
        fun canonicalBytes(e: EventEntity): ByteArray {
            val fields = listOf(
                "v1", e.id, e.deviceId, e.seq.toString(), e.lamport.toString(), e.authorUserId,
                e.entityType, e.entityId, e.op, e.baseEventId ?: "", e.createdAt.toString(), e.publicKey, e.payload,
            )
            return fields.joinToString("") { "${it.length}:$it;" }.toByteArray(Charsets.UTF_8)
        }

        fun verify(e: EventEntity): Boolean =
            CryptoUtil.deviceIdFor(e.publicKey) == e.deviceId &&
                CryptoUtil.verify(e.publicKey, canonicalBytes(e), e.signature)
        // TODO(identity): also verify that e.deviceId is a device linked to e.authorUserId
        //  (device-link events signed by an existing device of that user). V1 trusts authorUserId.
    }
}
