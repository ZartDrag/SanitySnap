package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.ChallengePayload
import com.rupeewise.sanitysnap.domain.ChallengeStatus
import com.rupeewise.sanitysnap.domain.ConflictText
import com.rupeewise.sanitysnap.domain.DiffSegment
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.ExpensePayload
import com.rupeewise.sanitysnap.domain.FriendshipPayload
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.GroupMemberPayload
import com.rupeewise.sanitysnap.domain.GroupPayload
import com.rupeewise.sanitysnap.domain.Ledger
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.ResolvePayload
import com.rupeewise.sanitysnap.domain.UserPayload
import com.rupeewise.sanitysnap.domain.diffFields
import com.rupeewise.sanitysnap.domain.expenseSnapshot
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------ serializable summary model
// The rendered summary is stored as JSON in sync_history, so reopening a past sync shows exactly
// what was shown then, even after later events change the projections.

object SummaryKind {
    const val EXPENSE_NEW = "EXPENSE_NEW"
    const val EXPENSE_EDIT = "EXPENSE_EDIT"
    const val EXPENSE_DELETE = "EXPENSE_DELETE"
    const val EXPENSE_RESOLVE = "EXPENSE_RESOLVE"
    const val PAYMENT = "PAYMENT"
    const val PAYMENT_IGNORED = "PAYMENT_IGNORED"
    const val PERSON = "PERSON"
    const val FRIEND = "FRIEND"
    const val GROUP = "GROUP"
    const val MEMBER = "MEMBER"
    const val CHALLENGE_RAISED = "CHALLENGE_RAISED"
    const val CHALLENGE_CLOSED = "CHALLENGE_CLOSED"
    const val OTHER = "OTHER"
}

@Serializable
data class SplitLine(val name: String, val amount: String, val isMe: Boolean)

@Serializable
data class SummaryFieldChange(
    val label: String,
    /** Text fields: inline diff (old text = SAME+REMOVED, new = SAME+ADDED). */
    val segments: List<DiffSegment>? = null,
    val before: String? = null,
    val after: String? = null,
    /** Amounts: "+₹500.00". */
    val delta: String? = null,
    /** Splits/payers: only the people whose amount changed, e.g. "@riya: ₹450.00 → ₹500.00". */
    val people: List<String> = emptyList(),
)

@Serializable
data class SummaryItem(
    val eventId: String,
    val kind: String,
    val title: String,
    val details: List<String> = emptyList(),
    val split: List<SplitLine> = emptyList(),
    val changes: List<SummaryFieldChange> = emptyList(),
    /** "@riya on device 450b1e" */
    val origin: String,
    /** True when the event was written on a device other than the peer's (passed along by gossip). */
    val relayed: Boolean,
    /** Set for expense items: enables Challenge / challenge status. */
    val expenseId: String? = null,
)

@Serializable
data class SummarySection(val title: String, val items: List<SummaryItem>)

@Serializable
data class BalanceChange(val friend: String, val friendUserId: String, val beforeMinor: Long, val afterMinor: Long) {
    /** Positive = they owe you. */
    val text: String
        get() {
            fun s(v: Long) = when {
                v > 0 -> "owes you ${Money.format(v)}"
                v < 0 -> "you owe ${Money.format(-v)}"
                else -> "settled"
            }
            val d = afterMinor - beforeMinor
            return "$friend: ${s(beforeMinor)} → ${s(afterMinor)} (${if (d >= 0) "+" else "−"}${Money.format(kotlin.math.abs(d))} for you)"
        }
}

@Serializable
data class ConflictRef(val conflictId: String, val title: String)

@Serializable
data class SyncSummary(
    val peerName: String,
    val peerDevice: String,
    val sections: List<SummarySection> = emptyList(),
    val balanceChanges: List<BalanceChange> = emptyList(),
    val newConflicts: List<ConflictRef> = emptyList(),
    val directCount: Int = 0,
    val relayedCount: Int = 0,
) {
    val upToDate: Boolean get() = sections.isEmpty() && newConflicts.isEmpty()
}

@Serializable
data class SettlementRecord(val status: String, val text: String, val paymentId: String? = null)

// ------------------------------------------------------------------ builder

/** Builds the post-sync "what exactly changed" summary from the newly received events. */
object ChangeSummary {

    /** State captured right before a sync, to compute balance changes and new conflicts. */
    data class Before(val ledger: Ledger, val openConflictIds: Set<String>)

    suspend fun capture(repo: FairShareRepository) = Before(repo.currentLedger(), repo.db.conflictDao().open().map { it.id }.toSet())

    private const val PEOPLE = "Friends & people"

    suspend fun build(
        repo: FairShareRepository,
        before: Before,
        events: List<EventEntity>,
        peerDeviceId: String?,
        peerName: String,
    ): SyncSummary {
        val me = repo.getProfile()?.userId
        suspend fun name(id: String) = if (id == me) "You" else repo.userName(id)
        suspend fun groupTitle(gid: String?) = gid?.let { "Group \u201C${repo.db.groupDao().byId(it)?.name ?: it.take(8)}\u201D" }
        suspend fun withTitle(ids: Collection<String>): String {
            val others = ids.filter { it != me }.distinct().map { repo.userName(it) }
            return if (others.isEmpty()) "Just you" else "With " + ConflictText.joinAnd(others)
        }
        fun stateOf(e: EventEntity) = if (e.op == Op.RESOLVE) FsJson.decodeFromString<ResolvePayload>(e.payload).state else e.payload

        val buckets = linkedMapOf<String, MutableList<SummaryItem>>()
        var direct = 0
        var relayed = 0
        for (e in events.sortedWith(compareBy({ it.lamport }, { it.deviceId }, { it.seq }))) {
            val author = name(e.authorUserId)
            val isRelayed = peerDeviceId != null && e.deviceId != peerDeviceId
            if (isRelayed) relayed++ else direct++
            val origin = "$author on device ${e.deviceId.take(6)}" + if (isRelayed) " · relayed by $peerName" else " · from $peerName directly"
            val built: Pair<String, SummaryItem>? = runCatching {
                when (e.entityType) {
                    EntityType.USER -> {
                        val p = FsJson.decodeFromString<UserPayload>(e.payload)
                        PEOPLE to SummaryItem(e.id, SummaryKind.PERSON, "New person: @${p.username}" + if (p.placeholder) " (unverified)" else "", origin = origin, relayed = isRelayed)
                    }
                    EntityType.FRIENDSHIP -> {
                        val p = FsJson.decodeFromString<FriendshipPayload>(e.payload)
                        PEOPLE to SummaryItem(e.id, SummaryKind.FRIEND, "${name(p.userIdA)} and ${name(p.userIdB)} are now friends", origin = origin, relayed = isRelayed)
                    }
                    EntityType.GROUP -> if (e.op == Op.ADD_MEMBER) {
                        val p = FsJson.decodeFromString<GroupMemberPayload>(e.payload)
                        groupTitle(p.groupId)!! to SummaryItem(e.id, SummaryKind.MEMBER, "$author added ${name(p.userId)} to the group", origin = origin, relayed = isRelayed)
                    } else {
                        val p = FsJson.decodeFromString<GroupPayload>(stateOf(e))
                        val verb = when (e.op) { Op.CREATE -> "created"; Op.DELETE -> "deleted"; else -> "edited" }
                        groupTitle(p.id)!! to SummaryItem(
                            e.id, SummaryKind.GROUP, "$author $verb group \u201C${p.name}\u201D",
                            details = listOf("Members: " + p.memberIds.map { name(it) }.joinToString()), origin = origin, relayed = isRelayed,
                        )
                    }
                    EntityType.EXPENSE -> expenseItem(repo, e, ::stateOf, ::name, me, author, origin, isRelayed).let { item ->
                        val p = FsJson.decodeFromString<ExpensePayload>(stateOf(e))
                        (groupTitle(p.groupId) ?: withTitle(p.payers.map { it.userId } + p.shares.map { it.userId })) to item
                    }
                    EntityType.PAYMENT -> {
                        val p = FsJson.decodeFromString<PaymentPayload>(e.payload)
                        val valid = Settlement.proofProblem(e, p) == null
                        val line = "${name(p.fromUserId)} paid ${name(p.toUserId)} ${Money.format(p.amountMinor, p.currency)}"
                        (groupTitle(p.groupId) ?: withTitle(listOf(p.fromUserId, p.toUserId))) to SummaryItem(
                            e.id, if (valid) SummaryKind.PAYMENT else SummaryKind.PAYMENT_IGNORED,
                            if (valid) "Settled up: $line" else "Ignored one-sided payment record: $line",
                            details = if (valid) listOf("Co-signed by both phones" + if (p.groupId == null) " · overall balance" else "") else listOf("Not co-signed, so it doesn't count"),
                            origin = origin, relayed = isRelayed,
                        )
                    }
                    EntityType.CHALLENGE -> {
                        val p = FsJson.decodeFromString<ChallengePayload>(e.payload)
                        val exp = repo.db.expenseDao().byId(p.expenseId)
                        val expTitle = "\u201C${exp?.description ?: "an expense"}\u201D"
                        val section = groupTitle(p.groupId) ?: withTitle(listOfNotNull(e.authorUserId))
                        if (e.op == Op.CREATE) {
                            section to SummaryItem(e.id, SummaryKind.CHALLENGE_RAISED, "$author challenged $expTitle", details = listOf("Reason: ${p.reason}"), origin = origin, relayed = isRelayed, expenseId = p.expenseId)
                        } else {
                            val st = when (p.status) { ChallengeStatus.RESOLVED -> "resolved"; ChallengeStatus.WITHDRAWN -> "withdrew"; else -> "reopened" }
                            section to SummaryItem(
                                e.id, SummaryKind.CHALLENGE_CLOSED,
                                if (st == "withdrew") "$author withdrew the challenge on $expTitle" else "$author $st the challenge on $expTitle",
                                details = listOfNotNull(p.resolutionNote?.let { "Note: $it" }), origin = origin, relayed = isRelayed,
                            )
                        }
                    }
                    else -> PEOPLE to SummaryItem(e.id, SummaryKind.OTHER, "${e.entityType} ${e.op}", origin = origin, relayed = isRelayed)
                }
            }.getOrNull()
            val (section, item) = built ?: (PEOPLE to SummaryItem(e.id, SummaryKind.OTHER, "${e.entityType} ${e.op}", origin = origin, relayed = isRelayed))
            buckets.getOrPut(section) { mutableListOf() } += item
        }

        // Groups and friends first (in order of appearance), people last
        val sections = buckets.entries.sortedBy { if (it.key == PEOPLE) 1 else 0 }.map { SummarySection(it.key, it.value) }

        val after = repo.currentLedger()
        val balances = if (me == null) emptyList() else {
            (before.ledger.counterparties(me) + after.counterparties(me)).distinct().mapNotNull { o ->
                val b = before.ledger.netOwed(o, me)
                val a = after.netOwed(o, me)
                if (a == b) null else BalanceChange(repo.userName(o), o, b, a)
            }.sortedBy { it.friend }
        }

        val conflicts = repo.db.conflictDao().open().filter { it.id !in before.openConflictIds }.map { c ->
            val desc = runCatching { FsJson.decodeFromString<ExpensePayload>(c.oursPayload).description }.getOrNull()
            ConflictRef(c.id, "${c.entityType.lowercase().replaceFirstChar { it.uppercase() }}" + (desc?.let { " \u201C$it\u201D" } ?: ""))
        }
        return SyncSummary(peerName, peerDeviceId?.take(6) ?: "", sections, balances, conflicts, direct, relayed)
    }

    private suspend fun expenseItem(
        repo: FairShareRepository,
        e: EventEntity,
        stateOf: (EventEntity) -> String,
        name: suspend (String) -> String,
        me: String?,
        author: String,
        origin: String,
        relayed: Boolean,
    ): SummaryItem {
        val p = FsJson.decodeFromString<ExpensePayload>(stateOf(e))
        val names = mutableMapOf<String, String>()
        for (id in (p.payers.map { it.userId } + p.shares.map { it.userId })) names[id] = name(id)
        val baseEv = e.baseEventId?.let { repo.db.eventDao().byId(it) }
        val baseP = baseEv?.let { runCatching { FsJson.decodeFromString<ExpensePayload>(stateOf(it)) }.getOrNull() }
        baseP?.let { b -> for (id in (b.payers.map { it.userId } + b.shares.map { it.userId })) names.getOrPut(id) { name(id) } }
        val gnames = mutableMapOf<String, String?>()
        for (gid in listOfNotNull(p.groupId, baseP?.groupId)) gnames[gid] = repo.db.groupDao().byId(gid)?.name
        val groupName: (String) -> String? = { gnames[it] }
        val amount = Money.format(p.amountMinor, p.currency)
        val split = p.shares.map { SplitLine(names[it.userId]!!, Money.format(it.amountMinor, p.currency), it.userId == me) }
        val paidBy = "Paid by " + p.payers.joinToString { "${names[it.userId]} ${Money.format(it.amountMinor, p.currency)}" }
        val myShare = p.shares.firstOrNull { it.userId == me }?.let { "Your share: ${Money.format(it.amountMinor, p.currency)}" }
        return when {
            e.op == Op.CREATE -> SummaryItem(
                e.id, SummaryKind.EXPENSE_NEW, "$author added \u201C${p.description}\u201D $amount",
                details = listOfNotNull(paidBy, "Split ${p.splitType.lowercase()}", myShare), split = split,
                origin = origin, relayed = relayed, expenseId = p.id,
            )
            p.deleted && baseP?.deleted != true && e.op != Op.RESOLVE -> SummaryItem(
                e.id, SummaryKind.EXPENSE_DELETE, "$author deleted \u201C${p.description}\u201D $amount",
                details = listOf(paidBy), origin = origin, relayed = relayed,
            )
            else -> {
                val changes = if (baseP != null) {
                    diffFields(expenseSnapshot(baseP, { names[it] ?: it }, groupName), expenseSnapshot(p, { names[it] ?: it }, groupName)).map { ch ->
                        SummaryFieldChange(
                            label = ch.label,
                            segments = ch.segments,
                            before = ch.before?.display,
                            after = ch.after?.display,
                            delta = ch.deltaMinor?.let { ConflictText.deltaText(it, p.currency) },
                            people = ch.people.orEmpty().map { pc ->
                                "${pc.name}: ${pc.before?.let { Money.format(it, p.currency) } ?: "not included"} → ${pc.after?.let { Money.format(it, p.currency) } ?: "removed"}"
                            },
                        )
                    }
                } else emptyList()
                val resolve = e.op == Op.RESOLVE
                SummaryItem(
                    e.id, if (resolve) SummaryKind.EXPENSE_RESOLVE else SummaryKind.EXPENSE_EDIT,
                    if (resolve) "$author resolved a conflict on \u201C${p.description}\u201D" else "$author edited \u201C${baseP?.description ?: p.description}\u201D",
                    details = listOfNotNull(if (changes.isEmpty()) "No visible field changes" else null, myShare),
                    changes = changes, origin = origin, relayed = relayed, expenseId = p.id.takeIf { !p.deleted },
                )
            }
        }
    }
}
