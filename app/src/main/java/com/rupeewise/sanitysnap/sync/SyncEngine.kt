package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.SettlementTerms
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Wire protocol messages (JSON, class discriminator "type"). */
@Serializable
sealed class SyncMessage {
    @Serializable @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val userId: String?,
        val username: String?,
        val deviceId: String,
        val publicKey: String,
        /** deviceId -> highest seq known. Lets the peer send only what we lack (incl. 3rd-party events). */
        val versionVector: Map<String, Long>,
        val eventCount: Int,
    ) : SyncMessage()

    @Serializable @SerialName("confirm")
    data class Confirm(val accepted: Boolean) : SyncMessage()

    @Serializable @SerialName("events")
    data class Events(val events: List<WireEvent>) : SyncMessage()

    @Serializable @SerialName("done")
    data class Done(val inserted: Int, val rejected: Int) : SyncMessage()

    // ---- Live session (after the initial sync the channel stays open; see LiveSession) ----

    /** In-session full re-sync request: "here is my version vector". */
    @Serializable @SerialName("session_sync")
    data class SessionSync(val versionVector: Map<String, Long>) : SyncMessage()

    /** Reply: every event the requester lacks + my version vector. Requester answers with EVENTS, then DONE. */
    @Serializable @SerialName("session_sync_reply")
    data class SessionSyncReply(val events: List<WireEvent>, val versionVector: Map<String, Long>) : SyncMessage()

    /** The peer is already running a sync/settlement in this session. */
    @Serializable @SerialName("session_busy")
    data class SessionBusy(val reason: String) : SyncMessage()

    /** Settle-up step b: proposer sends the terms (incl. its balance hash) signed by its device. */
    @Serializable @SerialName("settle_propose")
    data class SettlePropose(val terms: SettlementTerms, val proposerPublicKey: String, val proposerSignature: String) : SyncMessage()

    /** Step c (no): validation failed (hash mismatch, open conflict/challenge) or the user declined. */
    @Serializable @SerialName("settle_reject")
    data class SettleReject(val settlementId: String, val reason: String) : SyncMessage()

    /** Step c (yes): accepter's device co-signs the same terms. */
    @Serializable @SerialName("settle_accept")
    data class SettleAccept(val settlementId: String, val accepterPublicKey: String, val accepterSignature: String) : SyncMessage()

    /** Step d1: proposer sends the complete PAYMENT event carrying both signatures (not committed yet). */
    @Serializable @SerialName("settle_final")
    data class SettleFinal(val settlementId: String, val event: WireEvent) : SyncMessage()

    /** Step d2: accepter verified the final event and is ready to commit. */
    @Serializable @SerialName("settle_ack")
    data class SettleAck(val settlementId: String) : SyncMessage()

    /** Step d3: proposer committed; accepter commits the same event now. */
    @Serializable @SerialName("settle_commit")
    data class SettleCommit(val settlementId: String) : SyncMessage()

    /** Either side gives up; the pending (uncommitted) event is discarded. */
    @Serializable @SerialName("settle_abort")
    data class SettleAbort(val settlementId: String, val reason: String) : SyncMessage()

    /** Graceful end of the live session. */
    @Serializable @SerialName("bye")
    data object Bye : SyncMessage()
}

object SyncWire {
    fun encode(m: SyncMessage): String = FsJson.encodeToString(SyncMessage.serializer(), m)
    fun decode(s: String): SyncMessage = FsJson.decodeFromString(SyncMessage.serializer(), s)
}

data class PeerInfo(val userId: String?, val username: String?, val deviceId: String, val eventCount: Int, val keyFingerprint: String)

enum class SyncMode {
    /** Normal friend/own-device sync: both sides must already have a profile. */
    NORMAL,
    /** Fresh install "existing user": adopt the peer's account identity, then full merge. */
    ADOPT_PEER_IDENTITY,
}

data class SyncResult(
    val peer: PeerInfo?,
    val newEvents: List<EventEntity>,
    val sent: Int,
    val rejected: Int,
    val aborted: String? = null,
)

/**
 * Symmetric gossip-merge protocol. Both peers run [runSession] concurrently on the two ends of a
 * [SyncChannel]:
 *
 *  1. HELLO    exchange identity + version vectors
 *  2. CONFIRM  each side's user confirms the peer (fingerprint shown) — both must accept
 *  3. (friend sync) both sides emit a FRIENDSHIP event if needed
 *  4. EVENTS   each side sends every event the peer lacks per the version vector. This includes
 *              events authored by *other* devices, so changes spread transitively A→B→C.
 *  5. VERIFY   signatures + deviceId/key binding are checked; duplicates ignored
 *  6. REBUILD  projections are replayed from the merged log; divergent edits become conflicts
 *  7. DONE     counts exchanged; UI shows the post-sync change summary
 */
class SyncEngine(private val repo: FairShareRepository) {

    private fun encode(m: SyncMessage) = SyncWire.encode(m)
    private fun decode(s: String) = SyncWire.decode(s)

    suspend fun runSession(
        channel: SyncChannel,
        mode: SyncMode = SyncMode.NORMAL,
        confirmPeer: suspend (PeerInfo) -> Boolean,
        log: (String) -> Unit = {},
        /** Keep the channel open afterwards so a [LiveSession] (settle-up) can continue on it. */
        keepOpen: Boolean = false,
    ): SyncResult {
        val db = repo.db
        val signer = repo.signer
        var profile = repo.getProfile()
        if (profile == null && mode == SyncMode.NORMAL) {
            return SyncResult(null, emptyList(), 0, 0, aborted = "No local profile")
        }

        // 1. HELLO
        val myVv = db.eventDao().versionVector().associate { it.deviceId to it.maxSeq }
        channel.send(
            encode(
                SyncMessage.Hello(
                    PROTOCOL_VERSION, profile?.userId, profile?.username, signer.deviceId, signer.publicKeyB64,
                    myVv, myVv.values.sum().toInt(),
                )
            )
        )
        log("→ HELLO sent (device ${signer.deviceId}, ${myVv.size} device(s) in version vector)")
        val hello = decode(channel.receive()) as? SyncMessage.Hello
            ?: return SyncResult(null, emptyList(), 0, 0, aborted = "Protocol error: expected HELLO")
        if (CryptoUtil.deviceIdFor(hello.publicKey) != hello.deviceId) {
            return SyncResult(null, emptyList(), 0, 0, aborted = "Peer deviceId does not match its public key")
        }
        val peer = PeerInfo(hello.userId, hello.username, hello.deviceId, hello.eventCount, CryptoUtil.sha256Hex(CryptoUtil.unb64(hello.publicKey)).take(8).uppercase())
        log("← HELLO from @${hello.username ?: "(new device)"} device ${hello.deviceId}, fingerprint ${peer.keyFingerprint}")

        // 2. CONFIRM (both sides)
        val accepted = confirmPeer(peer)
        channel.send(encode(SyncMessage.Confirm(accepted)))
        val peerConfirm = decode(channel.receive()) as? SyncMessage.Confirm
        log("Peer confirmation: me=${if (accepted) "accepted" else "rejected"}, peer=${if (peerConfirm?.accepted == true) "accepted" else "rejected"}")
        if (!accepted || peerConfirm?.accepted != true) {
            channel.close()
            return SyncResult(peer, emptyList(), 0, 0, aborted = "Sync cancelled during peer confirmation")
        }

        // 3. Identity / friendship
        if (mode == SyncMode.ADOPT_PEER_IDENTITY && profile == null) {
            val uid = hello.userId
            val uname = hello.username
            if (uid == null || uname == null) {
                return SyncResult(peer, emptyList(), 0, 0, aborted = "Peer has no profile to adopt")
            }
            repo.adoptIdentity(uid, uname)
            profile = repo.getProfile()
            log("Adopted account @$uname on this new device (full merge follows)")
        } else if (profile != null && hello.userId != null && hello.userId != profile.userId) {
            repo.ensureFriendship(hello.userId)
            log("Friendship with @${hello.username} ensured")
        }

        // 4. EVENTS: everything the peer lacks according to its version vector
        val toSend = mutableListOf<EventEntity>()
        db.eventDao().versionVector().forEach { dv ->
            val peerHas = hello.versionVector[dv.deviceId] ?: 0L
            if (dv.maxSeq > peerHas) toSend += db.eventDao().forDeviceAfter(dv.deviceId, peerHas)
        }
        channel.send(encode(SyncMessage.Events(toSend.map { it.toWire() })))
        log("→ EVENTS sent: ${toSend.size}")
        val incoming = decode(channel.receive()) as? SyncMessage.Events
            ?: return SyncResult(peer, emptyList(), toSend.size, 0, aborted = "Protocol error: expected EVENTS")
        log("← EVENTS received: ${incoming.events.size}")

        // 5. VERIFY + insert
        val result = repo.eventLog.insertRemote(incoming.events.map { it.toEntity() })
        log("Verified signatures: ${result.inserted.size} new, ${incoming.events.size - result.inserted.size - result.rejected} duplicate, ${result.rejected} rejected")

        // 6. REBUILD
        repo.projector.rebuild(signer.deviceId)
        log("Projections rebuilt from merged log; conflict detection done")

        // 7. DONE
        channel.send(encode(SyncMessage.Done(result.inserted.size, result.rejected)))
        val done = decode(channel.receive()) as? SyncMessage.Done
        log("← DONE (peer inserted ${done?.inserted ?: "?"} of my events)")
        if (!keepOpen) channel.close()
        return SyncResult(peer, result.inserted, toSend.size, result.rejected)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
