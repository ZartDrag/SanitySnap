package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.crypto.BackupCrypto
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.FsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Manual encrypted backup. Because state = replay(event log), a backup is simply the full signed
 * event log plus the account identity. Import merges (never overwrites), so importing an older
 * backup into a newer install is safe.
 *
 * Sync history (sync_history table) is deliberately NOT included: it is per-device metadata
 * (which peer devices THIS phone met, and when). It is not part of the account's state, and restoring
 * it onto another phone would list syncs that never happened there. Balances and summaries can always be
 * re-derived from the event log, which is fully backed up. [SKIPPED_TABLES] documents this.
 * TODO(drive): Google Drive upload/download is out of scope for V1.
 */
class BackupManager(private val repo: FairShareRepository) {

    @Serializable
    data class BackupContents(
        val schema: Int = 1,
        val exportedAt: Long,
        val userId: String,
        val username: String,
        val events: List<WireEvent>,
    )

    companion object {
        /** Local tables a backup intentionally leaves out. */
        val SKIPPED_TABLES = listOf("sync_history")
    }

    data class ImportResult(val inserted: Int, val rejected: Int, val adoptedUsername: String?)

    suspend fun export(password: CharArray): ByteArray {
        val profile = repo.getProfile() ?: error("No profile")
        val contents = BackupContents(
            exportedAt = System.currentTimeMillis(),
            userId = profile.userId,
            username = profile.username,
            events = repo.db.eventDao().allOrdered().map { it.toWire() },
        )
        val env = BackupCrypto.encrypt(FsJson.encodeToString(contents).toByteArray(), password)
        return FsJson.encodeToString(env).toByteArray()
    }

    suspend fun import(fileBytes: ByteArray, password: CharArray): ImportResult {
        val env = FsJson.decodeFromString<BackupCrypto.Envelope>(fileBytes.decodeToString())
        val plain = try {
            BackupCrypto.decrypt(env, password)
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password or corrupted file")
        }
        val contents = FsJson.decodeFromString<BackupContents>(plain.decodeToString())
        var adopted: String? = null
        if (repo.getProfile() == null) {
            repo.adoptIdentity(contents.userId, contents.username)
            adopted = contents.username
        }
        val res = repo.eventLog.insertRemote(contents.events.map { it.toEntity() })
        repo.projector.rebuild(repo.signer.deviceId)
        return ImportResult(res.inserted.size, res.rejected, adopted)
    }
}
