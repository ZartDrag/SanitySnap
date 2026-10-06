package com.rupeewise.sanitysnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM local_profile WHERE id = 0")
    fun observe(): Flow<LocalProfileEntity?>

    @Query("SELECT * FROM local_profile WHERE id = 0")
    suspend fun get(): LocalProfileEntity?

    @Upsert
    suspend fun upsert(profile: LocalProfileEntity)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY username")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun all(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun byId(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE lower(username) = lower(:username) LIMIT 1")
    suspend fun byUsername(username: String): UserEntity?

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun clear()
}

@Dao
interface FriendshipDao {
    @Query("SELECT * FROM friendships")
    fun observeAll(): Flow<List<FriendshipEntity>>

    @Query("SELECT * FROM friendships WHERE id = :id")
    suspend fun byId(id: String): FriendshipEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(f: FriendshipEntity)

    @Query("DELETE FROM friendships")
    suspend fun clear()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM expense_groups WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM expense_groups WHERE id = :id")
    fun observe(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM expense_groups WHERE id = :id")
    suspend fun byId(id: String): GroupEntity?

    @Upsert
    suspend fun upsert(g: GroupEntity)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(m: GroupMemberEntity)

    @Query("DELETE FROM expense_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM group_members")
    suspend fun clearMembers()
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND groupId = :groupId ORDER BY createdAt DESC")
    fun observeForGroup(groupId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE deleted = 0")
    suspend fun allActive(): List<ExpenseEntity>

    @Upsert
    suspend fun upsert(e: ExpenseEntity)

    @Query("SELECT * FROM expense_payers")
    fun observeAllPayers(): Flow<List<ExpensePayerEntity>>

    @Query("SELECT * FROM expense_shares")
    fun observeAllShares(): Flow<List<ExpenseShareEntity>>

    @Query("SELECT * FROM expense_payers WHERE expenseId = :expenseId")
    suspend fun payers(expenseId: String): List<ExpensePayerEntity>

    @Query("SELECT * FROM expense_shares WHERE expenseId = :expenseId")
    suspend fun shares(expenseId: String): List<ExpenseShareEntity>

    @Query("SELECT * FROM expense_payers")
    suspend fun allPayers(): List<ExpensePayerEntity>

    @Query("SELECT * FROM expense_shares")
    suspend fun allShares(): List<ExpenseShareEntity>

    @Query("DELETE FROM expense_payers WHERE expenseId = :expenseId")
    suspend fun deletePayers(expenseId: String)

    @Query("DELETE FROM expense_shares WHERE expenseId = :expenseId")
    suspend fun deleteShares(expenseId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayers(p: List<ExpensePayerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(s: List<ExpenseShareEntity>)

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    @Query("DELETE FROM expense_payers")
    suspend fun clearPayers()

    @Query("DELETE FROM expense_shares")
    suspend fun clearShares()
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE deleted = 0")
    suspend fun allActive(): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun byId(id: String): PaymentEntity?

    @Upsert
    suspend fun upsert(p: PaymentEntity)

    @Query("DELETE FROM payments")
    suspend fun clear()
}

@Dao
interface ChallengeDao {
    @Query("SELECT * FROM challenges ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChallengeEntity>>

    @Query("SELECT * FROM challenges WHERE status = 'OPEN'")
    suspend fun open(): List<ChallengeEntity>

    @Query("SELECT * FROM challenges WHERE id = :id")
    suspend fun byId(id: String): ChallengeEntity?

    @Upsert
    suspend fun upsert(c: ChallengeEntity)

    @Query("DELETE FROM challenges")
    suspend fun clear()
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY lamport ASC, deviceId ASC, seq ASC")
    suspend fun allOrdered(): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY lamport DESC, deviceId DESC, seq DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: String): EventEntity?

    @Query("SELECT id FROM events")
    suspend fun allIds(): List<String>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM events WHERE deviceId = :deviceId")
    suspend fun maxSeq(deviceId: String): Long

    @Query("SELECT COALESCE(MAX(lamport), 0) FROM events")
    suspend fun maxLamport(): Long

    @Query("SELECT deviceId, MAX(seq) AS maxSeq FROM events GROUP BY deviceId")
    suspend fun versionVector(): List<DeviceSeq>

    @Query("SELECT * FROM events WHERE deviceId = :deviceId AND seq > :afterSeq ORDER BY seq")
    suspend fun forDeviceAfter(deviceId: String, afterSeq: Long): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE publicKey = :publicKey AND authorUserId = :userId")
    suspend fun countByKeyAndAuthor(publicKey: String, userId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: EventEntity): Long
}

data class DeviceSeq(val deviceId: String, val maxSeq: Long)

@Dao
interface HeadDao {
    @Query("SELECT * FROM entity_heads WHERE entityType = :type AND entityId = :id")
    suspend fun get(type: String, id: String): EntityHeadEntity?

    @Upsert
    suspend fun upsert(h: EntityHeadEntity)

    @Query("DELETE FROM entity_heads")
    suspend fun clear()
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY status DESC, detectedAt DESC")
    fun observeAll(): Flow<List<ConflictEntity>>

    @Query("SELECT COUNT(*) FROM conflicts WHERE status = 'OPEN'")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT * FROM conflicts WHERE status = 'OPEN'")
    suspend fun open(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun byId(id: String): ConflictEntity?

    @Upsert
    suspend fun upsert(c: ConflictEntity)

    @Query("DELETE FROM conflicts")
    suspend fun clear()
}

@Dao
interface SyncHistoryDao {
    @Query("SELECT * FROM sync_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SyncHistoryEntity>>

    @Query("SELECT * FROM sync_history ORDER BY timestamp DESC")
    suspend fun all(): List<SyncHistoryEntity>

    @Query("SELECT * FROM sync_history WHERE id = :id")
    fun observe(id: String): Flow<SyncHistoryEntity?>

    @Query("SELECT * FROM sync_history WHERE id = :id")
    suspend fun byId(id: String): SyncHistoryEntity?

    @Query("SELECT * FROM sync_history WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForSession(sessionId: String): SyncHistoryEntity?

    @Upsert
    suspend fun upsert(e: SyncHistoryEntity)

    @Query("DELETE FROM sync_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sync_history")
    suspend fun clear()
}
