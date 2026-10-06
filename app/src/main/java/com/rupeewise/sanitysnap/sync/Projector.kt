package com.rupeewise.sanitysnap.sync

import androidx.room.withTransaction
import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.data.db.ChallengeEntity
import com.rupeewise.sanitysnap.data.db.ConflictEntity
import com.rupeewise.sanitysnap.data.db.EntityHeadEntity
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.db.ExpenseEntity
import com.rupeewise.sanitysnap.data.db.ExpensePayerEntity
import com.rupeewise.sanitysnap.data.db.ExpenseShareEntity
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.db.FriendshipEntity
import com.rupeewise.sanitysnap.data.db.GroupEntity
import com.rupeewise.sanitysnap.data.db.GroupMemberEntity
import com.rupeewise.sanitysnap.data.db.PaymentEntity
import com.rupeewise.sanitysnap.data.db.UserEntity
import com.rupeewise.sanitysnap.domain.ChallengePayload
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FriendshipPayload
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.GroupMemberPayload
import com.rupeewise.sanitysnap.domain.GroupPayload
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.ResolvePayload
import com.rupeewise.sanitysnap.domain.UserPayload

/**
 * Materializes Room projection tables from the event log.
 *
 * Ordering is total and deterministic: (lamport, deviceId, seq). Divergent edits are applied
 * last-writer-wins as a *provisional* state and surfaced as [ConflictEntity] rows so the user can
 * pick ours/theirs (git-style). Every device derives identical projections + conflicts from the
 * same set of events, which is what makes gossip merge converge.
 */
class Projector(private val db: FairShareDatabase) {

    /** Applies a single (newest) event incrementally. Call inside a transaction. */
    suspend fun apply(e: EventEntity) {
        val snapshot = if (e.op == Op.RESOLVE) FsJson.decodeFromString<ResolvePayload>(e.payload).state else e.payload
        when (e.entityType) {
            EntityType.USER -> {
                val p = FsJson.decodeFromString<UserPayload>(snapshot)
                val existing = db.userDao().byId(p.id)
                if (existing != null && !existing.placeholder && p.placeholder) return
                db.userDao().upsert(
                    UserEntity(p.id, p.username, p.displayName, p.publicKey ?: existing?.publicKey, p.placeholder, existing?.createdAt ?: e.createdAt)
                )
            }
            EntityType.FRIENDSHIP -> {
                val p = FsJson.decodeFromString<FriendshipPayload>(snapshot)
                db.friendshipDao().insert(FriendshipEntity(e.entityId, p.userIdA, p.userIdB, e.authorUserId, e.createdAt))
            }
            EntityType.GROUP -> {
                if (e.op == Op.ADD_MEMBER) {
                    val p = FsJson.decodeFromString<GroupMemberPayload>(snapshot)
                    db.groupDao().insertMember(GroupMemberEntity(p.groupId, p.userId, e.createdAt))
                } else {
                    val p = FsJson.decodeFromString<GroupPayload>(snapshot)
                    val existing = db.groupDao().byId(p.id)
                    db.groupDao().upsert(
                        GroupEntity(p.id, p.name, p.currency, existing?.createdBy ?: e.authorUserId, existing?.createdAt ?: e.createdAt, p.deleted)
                    )
                    p.memberIds.forEach { db.groupDao().insertMember(GroupMemberEntity(p.id, it, e.createdAt)) }
                }
            }
            EntityType.EXPENSE -> {
                val p = FsJson.decodeFromString<ExpensePayload>(snapshot)
                val existing = db.expenseDao().byId(p.id)
                db.expenseDao().upsert(
                    ExpenseEntity(
                        id = p.id, groupId = p.groupId, description = p.description, amountMinor = p.amountMinor,
                        currency = p.currency, splitType = p.splitType,
                        createdBy = existing?.createdBy ?: e.authorUserId,
                        createdAt = existing?.createdAt ?: e.createdAt,
                        updatedAt = e.createdAt, deleted = p.deleted,
                    )
                )
                db.expenseDao().deletePayers(p.id)
                db.expenseDao().deleteShares(p.id)
                db.expenseDao().insertPayers(p.payers.map { ExpensePayerEntity(p.id, it.userId, it.amountMinor) })
                db.expenseDao().insertShares(p.shares.map { ExpenseShareEntity(p.id, it.userId, it.amountMinor, it.inputValue) })
            }
            EntityType.PAYMENT -> {
                // Payments are immutable, two-party settlements: only a CREATE carrying a valid
                // co-signed SettlementProof counts. Anything else (legacy one-sided payments,
                // forged or half-signed ones) is ignored and does not move the entity head.
                if (e.op != Op.CREATE) return
                val p = runCatching { FsJson.decodeFromString<PaymentPayload>(snapshot) }.getOrNull() ?: return
                if (!Settlement.isValid(db, e, p)) return
                val existing = db.paymentDao().byId(p.id)
                db.paymentDao().upsert(
                    PaymentEntity(
                        p.id, p.groupId, p.fromUserId, p.toUserId, p.amountMinor, p.currency, p.note,
                        existing?.createdBy ?: e.authorUserId, existing?.createdAt ?: e.createdAt, p.deleted,
                    )
                )
            }
            EntityType.CHALLENGE -> {
                val p = FsJson.decodeFromString<ChallengePayload>(snapshot)
                val existing = db.challengeDao().byId(p.id)
                db.challengeDao().upsert(
                    ChallengeEntity(
                        p.id, p.expenseId, p.groupId, p.raisedBy, p.reason, p.status, p.resolutionNote,
                        existing?.createdAt ?: e.createdAt, e.createdAt,
                    )
                )
            }
        }
        if (e.op in Op.HEAD_TRACKED) db.headDao().upsert(EntityHeadEntity(e.entityType, e.entityId, e.id))
    }

    /** Clears all projections and replays the whole log, then re-derives conflicts. */
    suspend fun rebuild(localDeviceId: String) = db.withTransaction {
        db.userDao().clear()
        db.friendshipDao().clear()
        db.groupDao().clearGroups()
        db.groupDao().clearMembers()
        db.expenseDao().clearExpenses()
        db.expenseDao().clearPayers()
        db.expenseDao().clearShares()
        db.paymentDao().clear()
        db.challengeDao().clear()
        db.headDao().clear()
        db.conflictDao().clear()
        val events = db.eventDao().allOrdered()
        events.forEach { apply(it) }
        detectConflicts(events, localDeviceId).forEach { db.conflictDao().upsert(it) }
    }

    companion object {
        fun conflictId(a: String, b: String): String =
            CryptoUtil.sha256Hex(listOf(a, b).sorted().joinToString("|").toByteArray()).take(24)

        private fun stateOf(e: EventEntity): String =
            if (e.op == Op.RESOLVE) FsJson.decodeFromString<ResolvePayload>(e.payload).state else e.payload

        /**
         * Two events conflict when they edit the same entity from the same base event (i.e. the
         * same parent) and produce different states. Pure function — every device computes the
         * same set. A RESOLVE event whose conflictId matches marks the conflict resolved.
         */
        fun detectConflicts(ordered: List<EventEntity>, localDeviceId: String): List<ConflictEntity> {
            val resolutions = ordered.filter { it.op == Op.RESOLVE }
                .associateBy { FsJson.decodeFromString<ResolvePayload>(it.payload).conflictId }
            val out = mutableListOf<ConflictEntity>()
            ordered.filter { it.op in Op.DIVERGENT && it.baseEventId != null }
                .groupBy { Triple(it.entityType, it.entityId, it.baseEventId) }
                .values.filter { it.size > 1 }
                .forEach { siblings ->
                    for (i in siblings.indices) for (j in i + 1 until siblings.size) {
                        val a = siblings[i]
                        val b = siblings[j]
                        if (stateOf(a) == stateOf(b)) continue
                        val (ours, theirs) = when {
                            b.deviceId == localDeviceId && a.deviceId != localDeviceId -> b to a
                            else -> a to b
                        }
                        val id = conflictId(a.id, b.id)
                        val res = resolutions[id]
                        out += ConflictEntity(
                            id = id, entityType = a.entityType, entityId = a.entityId,
                            oursEventId = ours.id, theirsEventId = theirs.id,
                            oursPayload = stateOf(ours), theirsPayload = stateOf(theirs),
                            oursDeviceId = ours.deviceId, theirsDeviceId = theirs.deviceId,
                            status = if (res != null) "RESOLVED" else "OPEN",
                            resolutionEventId = res?.id,
                            detectedAt = maxOf(a.createdAt, b.createdAt),
                        )
                    }
                }
            return out
        }
    }
}
