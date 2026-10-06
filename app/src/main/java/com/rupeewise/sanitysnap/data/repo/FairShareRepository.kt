package com.rupeewise.sanitysnap.data.repo

import androidx.room.withTransaction
import com.rupeewise.sanitysnap.crypto.DeviceSigner
import com.rupeewise.sanitysnap.data.db.ConflictEntity
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.db.ExpenseEntity
import com.rupeewise.sanitysnap.data.db.ExpensePayerEntity
import com.rupeewise.sanitysnap.data.db.ExpenseShareEntity
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.db.LocalProfileEntity
import com.rupeewise.sanitysnap.data.db.PaymentEntity
import com.rupeewise.sanitysnap.domain.AmountPart
import com.rupeewise.sanitysnap.domain.BalanceCalculator
import com.rupeewise.sanitysnap.domain.ChallengePayload
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.DEFAULT_CURRENCY
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.ExpenseForBalance
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FriendshipPayload
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.GroupMemberPayload
import com.rupeewise.sanitysnap.domain.GroupPayload
import com.rupeewise.sanitysnap.domain.Ledger
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.PaymentForBalance
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.ResolvePayload
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitCalculator
import com.rupeewise.sanitysnap.domain.SplitResult
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.domain.UserPayload
import com.rupeewise.sanitysnap.sync.EventLog
import com.rupeewise.sanitysnap.sync.Settlement
import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.domain.SettlementProof
import com.rupeewise.sanitysnap.domain.SettlementTerms
import com.rupeewise.sanitysnap.sync.Projector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Result type for user actions that can be rejected by business rules. */
data class ConflictDetail(
    val basePayload: String?,
    val baseEvent: EventEntity?,
    val oursEvent: EventEntity?,
    val theirsEvent: EventEntity?,
    val localDeviceId: String,
    val userNames: Map<String, String>,
    val groupNames: Map<String, String>,
)

sealed class ActionResult<out T> {
    data class Ok<T>(val value: T) : ActionResult<T>()
    data class Rejected(val reason: String) : ActionResult<Nothing>()
}

/**
 * Single entry point for all reads/writes of one "device" (the real phone, or an in-app
 * simulated peer). Every write = append signed event + apply to projections, atomically.
 */
class FairShareRepository(
    val db: FairShareDatabase,
    val signer: DeviceSigner,
) {
    val eventLog = EventLog(db, signer)
    val projector = Projector(db)

    // ---------- Observables ----------
    val profile: Flow<LocalProfileEntity?> = db.profileDao().observe()
    val users = db.userDao().observeAll()
    val friendships = db.friendshipDao().observeAll()
    val groups = db.groupDao().observeAll()
    val expenses = db.expenseDao().observeAll()
    val payments = db.paymentDao().observeAll()
    val challenges = db.challengeDao().observeAll()
    val conflicts: Flow<List<ConflictEntity>> = db.conflictDao().observeAll()
    val openConflictCount = db.conflictDao().observeOpenCount()
    val eventCount = db.eventDao().observeCount()
    val allPayers = db.expenseDao().observeAllPayers()
    val allShares = db.expenseDao().observeAllShares()
    fun recentEvents(limit: Int = 50): Flow<List<EventEntity>> = db.eventDao().observeRecent(limit)
    fun group(id: String) = db.groupDao().observe(id)
    fun groupMembers(id: String) = db.groupDao().observeMembers(id)
    fun groupExpenses(id: String) = db.expenseDao().observeForGroup(id)

    /** Live ledger across all expenses and payments. */
    val ledger: Flow<Ledger> = combine(
        db.expenseDao().observeAll(),
        db.expenseDao().observeAllPayers(),
        db.expenseDao().observeAllShares(),
        db.paymentDao().observeAll(),
    ) { exps, payers, shares, pays -> buildLedger(exps, payers, shares, pays) }

    // ---------- Profile / identity ----------
    suspend fun getProfile() = db.profileDao().get()

    private suspend fun me(): LocalProfileEntity =
        db.profileDao().get() ?: error("No local profile yet — complete onboarding first")

    suspend fun createProfile(username: String, displayName: String): LocalProfileEntity = db.withTransaction {
        val userId = UUID.randomUUID().toString()
        val profile = LocalProfileEntity(0, userId, username.trim(), signer.deviceId, signer.publicKeyB64, System.currentTimeMillis())
        db.profileDao().upsert(profile)
        write(
            EntityType.USER, userId, Op.CREATE,
            FsJson.encodeToString(UserPayload(userId, username.trim(), displayName.trim().ifEmpty { username.trim() }, signer.publicKeyB64)),
            author = userId,
        )
        profile
    }

    /**
     * Existing-user flows (backup import / sync with own device): this install keeps its own new
     * device key but adopts the account's userId + username.
     * TODO(identity): emit a DEVICE_LINK event signed by the old device so peers can verify.
     */
    suspend fun adoptIdentity(userId: String, username: String) {
        db.profileDao().upsert(
            LocalProfileEntity(0, userId, username, signer.deviceId, signer.publicKeyB64, System.currentTimeMillis())
        )
    }

    private suspend fun write(type: String, id: String, op: String, payload: String, author: String? = null): EventEntity {
        val e = eventLog.appendLocal(author ?: me().userId, type, id, op, payload)
        projector.apply(e)
        return e
    }

    // ---------- Friends ----------
    suspend fun addFriendByUsername(username: String, displayName: String = ""): ActionResult<String> = db.withTransaction {
        val me = me()
        val uname = username.trim().removePrefix("@")
        if (uname.isEmpty()) return@withTransaction ActionResult.Rejected("Username required")
        if (uname.equals(me.username, ignoreCase = true)) return@withTransaction ActionResult.Rejected("That's you")
        val existing = db.userDao().byUsername(uname)
        val friendId = existing?.id ?: UUID.randomUUID().also { id ->
            // Manual add = unverified placeholder until a P2P sync proves the key.
            write(EntityType.USER, id.toString(), Op.CREATE, FsJson.encodeToString(UserPayload(id.toString(), uname, displayName.ifBlank { uname }, null, true)))
        }.toString()
        ensureFriendshipInTx(me.userId, friendId)
        ActionResult.Ok(friendId)
    }

    suspend fun ensureFriendship(otherUserId: String) = db.withTransaction { ensureFriendshipInTx(me().userId, otherUserId) }

    private suspend fun ensureFriendshipInTx(a: String, b: String) {
        if (a == b) return
        val (x, y) = listOf(a, b).sorted()
        val fid = "$x|$y"
        if (db.friendshipDao().byId(fid) == null) {
            write(EntityType.FRIENDSHIP, fid, Op.CREATE, FsJson.encodeToString(FriendshipPayload(x, y)))
        }
    }

    // ---------- Groups ----------
    suspend fun createGroup(name: String, memberIds: List<String>, currency: String = DEFAULT_CURRENCY): String = db.withTransaction {
        val me = me()
        val id = UUID.randomUUID().toString()
        val members = (listOf(me.userId) + memberIds).distinct()
        write(EntityType.GROUP, id, Op.CREATE, FsJson.encodeToString(GroupPayload(id, name.trim(), currency, members)))
        id
    }

    suspend fun addGroupMember(groupId: String, userId: String) = db.withTransaction {
        write(EntityType.GROUP, groupId, Op.ADD_MEMBER, FsJson.encodeToString(GroupMemberPayload(groupId, userId)))
    }

    // ---------- Expenses ----------
    suspend fun addExpense(
        groupId: String?,
        description: String,
        amountMinor: Long,
        splitType: SplitType,
        payers: Map<String, Long>,
        participants: List<ShareInput>,
        currency: String = DEFAULT_CURRENCY,
        existingId: String? = null,
    ): ActionResult<String> {
        if (description.isBlank()) return ActionResult.Rejected("Description required")
        if (payers.values.sum() != amountMinor) return ActionResult.Rejected("Payer amounts must add up to the total")
        val split = SplitCalculator.compute(amountMinor, splitType, participants)
        val shares = when (split) {
            is SplitResult.Error -> return ActionResult.Rejected(split.message)
            is SplitResult.Ok -> split.shares
        }
        return db.withTransaction {
            val id = existingId ?: UUID.randomUUID().toString()
            val payload = ExpensePayload(
                id = id, groupId = groupId, description = description.trim(), amountMinor = amountMinor,
                currency = currency, splitType = splitType.name,
                payers = payers.filterValues { it > 0 }.map { AmountPart(it.key, it.value) },
                shares = shares.map { AmountPart(it.userId, it.amountMinor, it.inputValue) },
            )
            write(EntityType.EXPENSE, id, if (existingId == null) Op.CREATE else Op.UPDATE, FsJson.encodeToString(payload))
            ActionResult.Ok(id)
        }
    }

    suspend fun expenseSnapshot(id: String): ExpensePayload? {
        val e = db.expenseDao().byId(id) ?: return null
        return ExpensePayload(
            id = e.id, groupId = e.groupId, description = e.description, amountMinor = e.amountMinor,
            currency = e.currency, splitType = e.splitType,
            payers = db.expenseDao().payers(id).map { AmountPart(it.userId, it.amountMinor) },
            shares = db.expenseDao().shares(id).map { AmountPart(it.userId, it.amountMinor, it.inputValue) },
            deleted = e.deleted,
        )
    }

    /** Writes a full-snapshot UPDATE (used by edit + divergence demo). */
    suspend fun updateExpenseSnapshot(payload: ExpensePayload) = db.withTransaction {
        write(EntityType.EXPENSE, payload.id, Op.UPDATE, FsJson.encodeToString(payload))
    }

    suspend fun deleteExpense(id: String) = db.withTransaction {
        val snap = expenseSnapshot(id) ?: return@withTransaction
        write(EntityType.EXPENSE, id, Op.DELETE, FsJson.encodeToString(snap.copy(deleted = true)))
    }

    suspend fun expenseDetail(id: String): Triple<ExpenseEntity, List<ExpensePayerEntity>, List<ExpenseShareEntity>>? {
        val e = db.expenseDao().byId(id) ?: return null
        return Triple(e, db.expenseDao().payers(id), db.expenseDao().shares(id))
    }

    // ---------- Payments (settle up) ----------
    /** Open challenges block settling (product rule). */
    suspend fun blockingChallenges(groupId: String?, a: String, b: String): List<String> {
        return db.challengeDao().open().filter { c ->
            val exp = db.expenseDao().byId(c.expenseId) ?: return@filter false
            if (exp.deleted) return@filter false
            if (groupId != null) {
                exp.groupId == groupId
            } else {
                val involved = (db.expenseDao().payers(exp.id).map { it.userId } + db.expenseDao().shares(exp.id).map { it.userId }).toSet()
                a in involved && b in involved
            }
        }.map { it.id }
    }

    /**
     * OPEN sync conflicts that block settling between [a] and [b]: conflicts on an expense or payment
     * in [groupId] (group settlement) or, for an overall settlement, on any expense/payment that
     * involves both users. Either version of the conflicting entity counts.
     */
    suspend fun blockingConflicts(groupId: String?, a: String, b: String): List<String> =
        db.conflictDao().open().filter { c ->
            if (c.entityType != EntityType.EXPENSE && c.entityType != EntityType.PAYMENT) return@filter false
            listOf(c.oursPayload, c.theirsPayload).any { json ->
                val (gid, involved) = when (c.entityType) {
                    EntityType.EXPENSE -> runCatching { FsJson.decodeFromString<ExpensePayload>(json) }.getOrNull()
                        ?.let { it.groupId to (it.payers.map { p -> p.userId } + it.shares.map { s -> s.userId }).toSet() }
                    else -> runCatching { FsJson.decodeFromString<PaymentPayload>(json) }.getOrNull()
                        ?.let { it.groupId to setOf(it.fromUserId, it.toUserId) }
                } ?: return@any false
                if (groupId != null) gid == groupId else a in involved && b in involved
            }
        }.map { it.id }

    /** Null when [a] and [b] may settle; otherwise a user-facing reason. */
    suspend fun settlementBlocker(groupId: String?, a: String, b: String): String? {
        val conflicts = blockingConflicts(groupId, a, b)
        if (conflicts.isNotEmpty()) {
            return "Settling is blocked: ${conflicts.size} open sync conflict(s) on expenses or payments between you. Resolve them first."
        }
        val challenges = blockingChallenges(groupId, a, b)
        if (challenges.isNotEmpty()) {
            return "Settling is blocked: ${challenges.size} open challenge(s). Resolve them first."
        }
        return null
    }

    data class PairBalance(val groupId: String?, val owedFromTo: Long, val hash: String)

    /**
     * Balance between [from] and [to] (positive = [from] owes [to]) in [groupId] (null = overall,
     * all groups + non-group), plus a hash over it and over every expense/payment that feeds it
     * (id + current head event). Two devices with the same merged log compute the same hash.
     */
    suspend fun pairBalance(groupId: String?, from: String, to: String): PairBalance {
        val exps = db.expenseDao().allActive().filter { !it.deleted }
        val payers = db.expenseDao().allPayers()
        val shares = db.expenseDao().allShares()
        val pays = db.paymentDao().allActive()
        val ledger = buildLedger(exps, payers, shares, pays, groupId, filterByGroup = groupId != null)
        val (lo, hi) = listOf(from, to).sorted()
        val involvedBy = (payers.map { it.expenseId to it.userId } + shares.map { it.expenseId to it.userId })
            .groupBy({ it.first }, { it.second })
        val expParts = exps.filter { (groupId == null || it.groupId == groupId) && involvedBy[it.id].orEmpty().containsAll(listOf(lo, hi)) }
            .map { "${it.id}@${db.headDao().get(EntityType.EXPENSE, it.id)?.headEventId ?: ""}" }.sorted()
        val payParts = pays.filter { (groupId == null || it.groupId == groupId) && setOf(it.fromUserId, it.toUserId) == setOf(lo, hi) }
            .map { "${it.id}:${it.amountMinor}" }.sorted()
        val canonical = listOf("bal-v1", groupId ?: "*", lo, hi, ledger.netOwed(lo, hi).toString(), expParts.joinToString(","), payParts.joinToString(","))
            .joinToString("|")
        return PairBalance(groupId, ledger.netOwed(from, to), CryptoUtil.sha256Hex(canonical.toByteArray()))
    }

    /** Checks proposed terms against THIS device's state. Null = acceptable. */
    suspend fun validateSettlementTerms(t: SettlementTerms): String? {
        val me = getProfile()?.userId ?: return "No local profile"
        if (me != t.fromUserId && me != t.toUserId) return "This settlement does not involve you"
        if (t.amountMinor <= 0) return "Amount must be greater than zero"
        if (t.fromUserId == t.toUserId) return "Payer and receiver must differ"
        settlementBlocker(t.groupId, t.fromUserId, t.toUserId)?.let { return it }
        val bal = pairBalance(t.groupId, t.fromUserId, t.toUserId)
        if (bal.hash != t.balanceHash) return "Balances don't match between the two devices (hash mismatch). Sync again and retry."
        if (bal.owedFromTo <= 0) return "${userName(t.fromUserId)} doesn't owe ${userName(t.toUserId)} anything here"
        if (t.amountMinor > bal.owedFromTo) return "Amount is more than the balance (${com.rupeewise.sanitysnap.domain.Money.format(bal.owedFromTo)})"
        return null
    }

    /** Builds (signs, does NOT store) the single co-signed PAYMENT event. Proposer side only. */
    suspend fun buildSettlementEvent(proof: SettlementProof): EventEntity {
        val t = proof.terms
        val payload = PaymentPayload(t.settlementId, t.groupId, t.fromUserId, t.toUserId, t.amountMinor, t.currency, "Settle up", settlement = proof)
        return eventLog.buildLocal(me().userId, EntityType.PAYMENT, t.settlementId, Op.CREATE, FsJson.encodeToString(payload))
    }

    /** Commits the settlement event (own built event or the peer's). False if it isn't valid here. */
    suspend fun commitSettlementEvent(e: EventEntity): Boolean = db.withTransaction {
        val p = runCatching { FsJson.decodeFromString<PaymentPayload>(e.payload) }.getOrNull() ?: return@withTransaction false
        if (!Settlement.isValid(db, e, p)) return@withTransaction false
        val inserted = if (e.deviceId == signer.deviceId) {
            eventLog.commitBuilt(e)
        } else {
            eventLog.insertRemote(listOf(e)).inserted.isNotEmpty()
        }
        if (!inserted) return@withTransaction false
        projector.apply(e)
        true
    }

    // ---------- Challenges ----------
    suspend fun raiseChallenge(expenseId: String, reason: String): ActionResult<String> = db.withTransaction {
        val exp = db.expenseDao().byId(expenseId) ?: return@withTransaction ActionResult.Rejected("Expense not found")
        val id = UUID.randomUUID().toString()
        write(
            EntityType.CHALLENGE, id, Op.CREATE,
            FsJson.encodeToString(ChallengePayload(id, expenseId, exp.groupId, me().userId, reason.ifBlank { "Please double-check this expense" })),
        )
        ActionResult.Ok(id)
    }

    suspend fun setChallengeStatus(id: String, status: String, note: String?) = db.withTransaction {
        val c = db.challengeDao().byId(id) ?: return@withTransaction
        write(
            EntityType.CHALLENGE, id, Op.UPDATE,
            FsJson.encodeToString(ChallengePayload(c.id, c.expenseId, c.groupId, c.raisedBy, c.reason, status, note)),
        )
    }

    // ---------- Conflicts ----------
    /** Emits a RESOLVE "merge commit" carrying the chosen state, then re-derives projections. */
    suspend fun resolveConflict(conflictId: String, keepOurs: Boolean) {
        db.withTransaction {
            val c = db.conflictDao().byId(conflictId) ?: return@withTransaction
            if (c.status != "OPEN") return@withTransaction
            val state = if (keepOurs) c.oursPayload else c.theirsPayload
            write(
                c.entityType, c.entityId, Op.RESOLVE,
                FsJson.encodeToString(ResolvePayload(c.id, listOf(c.oursEventId, c.theirsEventId), if (keepOurs) "OURS" else "THEIRS", state)),
            )
        }
        projector.rebuild(signer.deviceId)
    }

    /** Everything the conflict screen needs to explain a conflict: the common base and who made each edit. */
    suspend fun conflictDetail(c: ConflictEntity): ConflictDetail {
        val ours = db.eventDao().byId(c.oursEventId)
        val theirs = db.eventDao().byId(c.theirsEventId)
        val baseEvent = (ours?.baseEventId ?: theirs?.baseEventId)?.let { db.eventDao().byId(it) }
        val basePayload = baseEvent?.let {
            if (it.op == Op.RESOLVE) FsJson.decodeFromString<ResolvePayload>(it.payload).state else it.payload
        }
        val names = db.userDao().all().associate { it.id to "@" + it.username }
        val groupNames = mutableMapOf<String, String>()
        listOfNotNull(basePayload, c.oursPayload, c.theirsPayload).forEach { json ->
            runCatching { FsJson.decodeFromString<ExpensePayload>(json).groupId }.getOrNull()?.let { gid ->
                db.groupDao().byId(gid)?.let { groupNames[gid] = it.name }
            }
        }
        return ConflictDetail(
            basePayload = basePayload,
            baseEvent = baseEvent,
            oursEvent = ours,
            theirsEvent = theirs,
            localDeviceId = signer.deviceId,
            userNames = names,
            groupNames = groupNames,
        )
    }

    // ---------- Ledger ----------
    suspend fun currentLedger(): Ledger = buildLedger(
        db.expenseDao().allActive(), db.expenseDao().allPayers(), db.expenseDao().allShares(), db.paymentDao().allActive(),
    )

    suspend fun userName(id: String): String = db.userDao().byId(id)?.let { "@" + it.username } ?: id.take(8)

    companion object {
        fun buildLedger(
            exps: List<ExpenseEntity>,
            payers: List<ExpensePayerEntity>,
            shares: List<ExpenseShareEntity>,
            pays: List<PaymentEntity>,
            groupId: String? = null,
            filterByGroup: Boolean = false,
        ): Ledger {
            val payersBy = payers.groupBy { it.expenseId }
            val sharesBy = shares.groupBy { it.expenseId }
            return BalanceCalculator.ledger(
                exps.filter { !it.deleted }.map { e ->
                    ExpenseForBalance(
                        e.id, e.groupId,
                        payersBy[e.id].orEmpty().associate { it.userId to it.amountMinor },
                        sharesBy[e.id].orEmpty().associate { it.userId to it.amountMinor },
                    )
                },
                pays.filter { !it.deleted }.map { PaymentForBalance(it.groupId, it.fromUserId, it.toUserId, it.amountMinor) },
                groupId, filterByGroup,
            )
        }
    }
}
