package com.rupeewise.sanitysnap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalProfileEntity::class,
        UserEntity::class,
        FriendshipEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ExpenseEntity::class,
        ExpensePayerEntity::class,
        ExpenseShareEntity::class,
        PaymentEntity::class,
        ChallengeEntity::class,
        EventEntity::class,
        EntityHeadEntity::class,
        ConflictEntity::class,
        SyncHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FairShareDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun userDao(): UserDao
    abstract fun friendshipDao(): FriendshipDao
    abstract fun groupDao(): GroupDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun paymentDao(): PaymentDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun eventDao(): EventDao
    abstract fun headDao(): HeadDao
    abstract fun conflictDao(): ConflictDao
    abstract fun syncHistoryDao(): SyncHistoryDao

    companion object {
        /** [name] lets the app host a second, simulated "peer device" database for fake sync. */
        fun build(context: Context, name: String): FairShareDatabase =
            Room.databaseBuilder(context.applicationContext, FairShareDatabase::class.java, name)
                // Real migrations only: a missing migration must fail loudly, never wipe the event log.
                .addMigrations(*MIGRATIONS)
                .build()

        /** v1 → v2: adds the local-only sync_history table. Nothing else changes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_history` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, `peerUserId` TEXT, `peerUsername` TEXT, `peerDeviceId` TEXT, " +
                        "`transport` TEXT NOT NULL, `outcome` TEXT NOT NULL, `message` TEXT, `sentCount` INTEGER NOT NULL, " +
                        "`receivedCount` INTEGER NOT NULL, `receivedEventIds` TEXT NOT NULL, `summaryJson` TEXT NOT NULL, " +
                        "`settlementsJson` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_history_timestamp` ON `sync_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_history_sessionId` ON `sync_history` (`sessionId`)")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
