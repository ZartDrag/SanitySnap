package com.rupeewise.sanitysnap.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room schema for FairShare.
 *
 * IMPORTANT: the append-only [EventEntity] log is the source of truth. Every other table
 * (users, friendships, groups, expenses, payments, challenges, conflicts, entity heads) is a
 * *projection* that can be rebuilt deterministically by replaying the log
 * (see com.rupeewise.sanitysnap.sync.Projector). Only [LocalProfileEntity] is device-local state
 * that is never synced.
 *
 * Money is stored as Long minor units (paise for INR). Every money row carries a currency code.
 */

/** This device's identity: one row (id = 0). Never synced. */
@Entity(tableName = "local_profile")
data class LocalProfileEntity(
    @PrimaryKey val id: Int = 0,
    val userId: String,
    val username: String,
    val deviceId: String,
    val devicePublicKey: String,
    val createdAt: Long,
)

@Entity(tableName = "users", indices = [Index("username")])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    /** Base64 X.509 public key of the device that registered this user; null for manual placeholders. */
    val publicKey: String?,
    /** True when added manually by username and not yet verified through a P2P sync. */
    val placeholder: Boolean,
    val createdAt: Long,
)

/** Symmetric friendship; id = sorted "a|b" so both sides converge on the same row. */
@Entity(tableName = "friendships")
data class FriendshipEntity(
    @PrimaryKey val id: String,
    val userIdA: String,
    val userIdB: String,
    val createdBy: String,
    val createdAt: Long,
)

@Entity(tableName = "expense_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val createdBy: String,
    val createdAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"], indices = [Index("userId")])
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    val addedAt: Long,
)

@Entity(tableName = "expenses", indices = [Index("groupId")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    /** Null = non-group expense between friends. */
    val groupId: String?,
    val description: String,
    val amountMinor: Long,
    val currency: String,
    /** EQUAL / EXACT / PERCENT / SHARES (see domain.SplitType). */
    val splitType: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Who paid how much for an expense (supports multiple payers). */
@Entity(tableName = "expense_payers", primaryKeys = ["expenseId", "userId"])
data class ExpensePayerEntity(
    val expenseId: String,
    val userId: String,
    val amountMinor: Long,
)

/** Who owes how much for an expense. [inputValue] keeps the raw percent/share weight entered. */
@Entity(tableName = "expense_shares", primaryKeys = ["expenseId", "userId"], indices = [Index("userId")])
data class ExpenseShareEntity(
    val expenseId: String,
    val userId: String,
    val amountMinor: Long,
    val inputValue: Double?,
)

@Entity(tableName = "payments", indices = [Index("groupId")])
data class PaymentEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val currency: String,
    val note: String,
    val createdBy: String,
    val createdAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "challenges", indices = [Index("expenseId")])
data class ChallengeEntity(
    @PrimaryKey val id: String,
    val expenseId: String,
    val groupId: String?,
    val raisedBy: String,
    val reason: String,
    /** OPEN / RESOLVED / WITHDRAWN. OPEN challenges block settling. */
    val status: String,
    val resolutionNote: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Append-only, signed event. (deviceId, seq) is unique and gap-free per device which lets peers
 * exchange compact version vectors during gossip.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["deviceId", "seq"], unique = true), Index(value = ["entityType", "entityId"])],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val seq: Long,
    val lamport: Long,
    val authorUserId: String,
    val entityType: String,
    val entityId: String,
    val op: String,
    /** JSON payload (full entity snapshot for CREATE/UPDATE/RESOLVE). */
    val payload: String,
    /** Head event of the entity this edit was based on (git-style parent). Null for CREATE. */
    val baseEventId: String?,
    val createdAt: Long,
    /** Base64 X.509 public key of the authoring device. deviceId = hash(publicKey). */
    val publicKey: String,
    /** Base64 SHA256withECDSA signature over the canonical event bytes. */
    val signature: String,
)

/** Current head event per entity, maintained by the projector. */
@Entity(tableName = "entity_heads", primaryKeys = ["entityType", "entityId"])
data class EntityHeadEntity(
    val entityType: String,
    val entityId: String,
    val headEventId: String,
)

/** Git-style divergence: two edits made from the same base on different devices. */
@Entity(tableName = "conflicts")
data class ConflictEntity(
    /** Deterministic: hash of the two event ids, so every device derives the same conflict. */
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val oursEventId: String,
    val theirsEventId: String,
    val oursPayload: String,
    val theirsPayload: String,
    val oursDeviceId: String,
    val theirsDeviceId: String,
    /** OPEN / RESOLVED */
    val status: String,
    val resolutionEventId: String?,
    val detectedAt: Long,
)

/**
 * One row per sync run on THIS device (initial connect-sync, in-session re-sync, settle-up re-sync).
 * Local-only metadata: never synced, NOT included in backups, and deleting rows never touches the
 * event log or any balance.
 */
@Entity(tableName = "sync_history", indices = [Index("timestamp"), Index("sessionId")])
data class SyncHistoryEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    /** Live session this run belonged to (several runs can share one session). */
    val sessionId: String,
    val peerUserId: String?,
    val peerUsername: String?,
    val peerDeviceId: String?,
    /** SIMULATED (in-app peer) or NEARBY (real transport, TODO(nearby)). */
    val transport: String,
    /** OK / FAILED / DROPPED */
    val outcome: String,
    val message: String?,
    val sentCount: Int,
    val receivedCount: Int,
    /** JSON array of the received event ids. */
    val receivedEventIds: String,
    /** The rendered [com.rupeewise.sanitysnap.sync.SyncSummary] as JSON (survives projection changes). */
    val summaryJson: String,
    /** JSON array of [com.rupeewise.sanitysnap.sync.SettlementRecord] made in this session. */
    val settlementsJson: String = "[]",
)
