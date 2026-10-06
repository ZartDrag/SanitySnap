package com.rupeewise.sanitysnap.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Entity types carried in the event log. */
object EntityType {
    const val USER = "USER"
    const val FRIENDSHIP = "FRIENDSHIP"
    const val GROUP = "GROUP"
    const val EXPENSE = "EXPENSE"
    const val PAYMENT = "PAYMENT"
    const val CHALLENGE = "CHALLENGE"
}

/** Operations. CREATE/UPDATE/DELETE/RESOLVE participate in git-style head tracking. */
object Op {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val ADD_MEMBER = "ADD_MEMBER"
    /** Merge commit that resolves a conflict; payload = [ResolvePayload]. */
    const val RESOLVE = "RESOLVE"

    val HEAD_TRACKED = setOf(CREATE, UPDATE, DELETE, RESOLVE)
    /** Ops that can diverge from a shared base and therefore conflict. */
    val DIVERGENT = setOf(UPDATE, DELETE, RESOLVE)
}

object ChallengeStatus {
    const val OPEN = "OPEN"
    const val RESOLVED = "RESOLVED"
    const val WITHDRAWN = "WITHDRAWN"
}

val FsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class UserPayload(
    val id: String,
    val username: String,
    val displayName: String,
    val publicKey: String? = null,
    val placeholder: Boolean = false,
)

@Serializable
data class FriendshipPayload(val userIdA: String, val userIdB: String)

@Serializable
data class GroupPayload(
    val id: String,
    val name: String,
    val currency: String = DEFAULT_CURRENCY,
    val memberIds: List<String> = emptyList(),
    val deleted: Boolean = false,
)

@Serializable
data class GroupMemberPayload(val groupId: String, val userId: String)

@Serializable
data class AmountPart(val userId: String, val amountMinor: Long, val inputValue: Double? = null)

@Serializable
data class ExpensePayload(
    val id: String,
    val groupId: String? = null,
    val description: String,
    val amountMinor: Long,
    val currency: String = DEFAULT_CURRENCY,
    val splitType: String = SplitType.EQUAL.name,
    val payers: List<AmountPart>,
    val shares: List<AmountPart>,
    val deleted: Boolean = false,
)

@Serializable
data class PaymentPayload(
    val id: String,
    val groupId: String? = null,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val currency: String = DEFAULT_CURRENCY,
    val note: String = "",
    val deleted: Boolean = false,
    /**
     * Two-party proof. Since the synchronous settle-up rule, a PAYMENT only counts toward balances
     * when it carries a [SettlementProof] signed by BOTH users' devices (see sync/Settlement.kt).
     */
    val settlement: SettlementProof? = null,
)

/** What both devices agree to when settling up. Signed (canonically encoded) by both sides. */
@Serializable
data class SettlementTerms(
    val settlementId: String,
    val groupId: String? = null,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val currency: String = DEFAULT_CURRENCY,
    /** SHA-256 over the pairwise balance both devices computed right before settling. */
    val balanceHash: String,
    val proposerUserId: String,
    val accepterUserId: String,
    val proposedAt: Long,
)

/** Both co-signatures over [SettlementTerms]; embedded in the single PAYMENT event. */
@Serializable
data class SettlementProof(
    val terms: SettlementTerms,
    val proposerPublicKey: String,
    val proposerSignature: String,
    val accepterPublicKey: String,
    val accepterSignature: String,
)

@Serializable
data class ChallengePayload(
    val id: String,
    val expenseId: String,
    val groupId: String? = null,
    val raisedBy: String,
    val reason: String,
    val status: String = ChallengeStatus.OPEN,
    val resolutionNote: String? = null,
)

@Serializable
data class ResolvePayload(
    val conflictId: String,
    val resolvedEventIds: List<String>,
    /** "OURS" / "THEIRS" from the resolver's point of view (informational). */
    val choice: String,
    /** Full entity snapshot JSON (same schema as the entity's CREATE/UPDATE payload). */
    val state: String,
)
