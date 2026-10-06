package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.domain.DEFAULT_CURRENCY
import com.rupeewise.sanitysnap.domain.FsJson
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.SettlementProof
import com.rupeewise.sanitysnap.domain.SettlementTerms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed class SettlementOutcome {
    data class Settled(val paymentId: String, val terms: SettlementTerms) : SettlementOutcome()
    /** Blocked by a rule (open conflict / challenge / hash mismatch) on this device or the peer's. */
    data class Blocked(val reason: String) : SettlementOutcome()
    data class Declined(val reason: String) : SettlementOutcome()
    /** Connection dropped or timed out before both sides had both parts: nothing was recorded. */
    data class Dropped(val reason: String) : SettlementOutcome()
    data class Failed(val reason: String) : SettlementOutcome()
}

data class SessionSyncSummary(val newEvents: List<EventEntity>, val sent: Int, val rejected: Int)

private class SessionClosed(msg: String) : Exception(msg)

/**
 * A live, connected session with one peer, started on the SAME [SyncChannel] right after a
 * successful [SyncEngine.runSession] (`keepOpen = true`). Both ends run one of these; the protocol
 * is symmetric, so either device can propose a settlement (the other one responds).
 *
 * Settle-up protocol (proposer A, accepter B):
 *  a. SESSION_SYNC / SESSION_SYNC_REPLY / EVENTS / DONE: full re-sync so logs + balances match
 *  b. SETTLE_PROPOSE  A → B  terms (payer, payee, amount, group, A's balance hash) + A's signature
 *  c. B validates (hash, open conflicts, open challenges), asks its user; SETTLE_ACCEPT carries
 *     B's co-signature over the same terms, or SETTLE_REJECT carries the reason
 *  d. SETTLE_FINAL    A → B  the single PAYMENT event (envelope signed by A, payload = both sigs)
 *     SETTLE_ACK      B → A  B verified it          → A commits
 *     SETTLE_COMMIT   A → B  A committed            → B commits the same event
 *  Nothing is written to either log before d. If the link drops earlier, the pending event only
 *  ever lived in memory and is discarded on both sides.
 */
class LiveSession(
    val repo: FairShareRepository,
    private val channel: SyncChannel,
    val peer: PeerInfo,
    private val scope: CoroutineScope,
    /** Ask THIS device's user to accept an incoming proposal (already validated). */
    private val confirmIncoming: suspend (SettlementTerms) -> Boolean,
    private val log: (String) -> Unit = {},
    /** Called when the peer drove a re-sync or a settlement finished on our side as accepter. */
    private val onPeerSynced: (SessionSyncSummary) -> Unit = {},
    /** Runs right before a peer-driven re-sync inserts anything (e.g. to capture pre-sync balances). */
    private val beforePeerSync: suspend () -> Unit = {},
    private val onIncomingOutcome: (SettlementOutcome) -> Unit = {},
    private val stepTimeoutMs: Long = 15_000,
    private val promptTimeoutMs: Long = 120_000,
) {
    private val replies = Channel<SyncMessage>(Channel.UNLIMITED)
    private val busy = Mutex()
    private var reader: Job? = null
    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    var closeReason: String? = null
        private set
    var onClosed: (String) -> Unit = {}

    /** Result of the most recent re-sync THIS side drove (also the one inside proposeSettlement). */
    var lastResync: SessionSyncSummary? = null
        private set

    fun start(): LiveSession {
        reader = scope.launch {
            try {
                while (true) {
                    val m = SyncWire.decode(channel.receive())
                    when (m) {
                        is SyncMessage.SessionSync -> scope.launch { respondToSync(m) }
                        is SyncMessage.SettlePropose -> scope.launch { respondToProposal(m) }
                        is SyncMessage.Bye -> { markClosed("Peer ended the session"); break }
                        else -> replies.send(m)
                    }
                }
            } catch (e: CancellationException) {
                markClosed(closeReason ?: "Session closed")
                throw e
            } catch (e: Exception) {
                markClosed("Connection lost")
            }
        }
        return this
    }

    private fun markClosed(reason: String) {
        if (!_connected.value) return
        _connected.value = false
        closeReason = reason
        replies.close(SessionClosed(reason))
        log("Session closed: $reason")
        onClosed(reason)
    }

    /** Ends the session gracefully (peer sees BYE). */
    suspend fun disconnect() {
        if (_connected.value) runCatching { channel.send(SyncWire.encode(SyncMessage.Bye)) }
        markClosed("Disconnected")
        channel.close()
        reader?.cancel()
    }

    private suspend fun send(m: SyncMessage) {
        if (!_connected.value) throw SessionClosed(closeReason ?: "Not connected")
        try {
            channel.send(SyncWire.encode(m))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markClosed("Connection lost")
            throw SessionClosed("Connection lost")
        }
    }

    private suspend fun next(timeoutMs: Long = stepTimeoutMs): SyncMessage = try {
        withTimeout(timeoutMs) { replies.receive() }
    } catch (e: TimeoutCancellationException) {
        throw SessionClosed("Peer did not answer in time")
    } catch (e: SessionClosed) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SessionClosed(closeReason ?: "Connection lost")
    }

    /** Drops stale replies left over from an earlier, aborted exchange. */
    private fun drain() {
        while (replies.tryReceive().isSuccess) Unit
    }

    private suspend fun myVersionVector() = repo.db.eventDao().versionVector().associate { it.deviceId to it.maxSeq }

    private suspend fun eventsLackedBy(vv: Map<String, Long>): List<EventEntity> {
        val out = mutableListOf<EventEntity>()
        repo.db.eventDao().versionVector().forEach { dv ->
            val has = vv[dv.deviceId] ?: 0L
            if (dv.maxSeq > has) out += repo.db.eventDao().forDeviceAfter(dv.deviceId, has)
        }
        return out
    }

    // ------------------------------------------------------------------ re-sync

    /** Step a: full gossip merge inside the session (same rules as [SyncEngine]). */
    suspend fun resync(): SessionSyncSummary = busy.withLock { resyncLocked() }

    private suspend fun resyncLocked(): SessionSyncSummary {
        drain()
        send(SyncMessage.SessionSync(myVersionVector()))
        lastResync = null
        val reply = when (val m = next()) {
            is SyncMessage.SessionSyncReply -> m
            is SyncMessage.SessionBusy -> throw IllegalStateException("Peer is busy: ${m.reason}")
            else -> throw IllegalStateException("Protocol error: expected SESSION_SYNC_REPLY, got ${m::class.simpleName}")
        }
        val res = repo.eventLog.insertRemote(reply.events.map { it.toEntity() })
        repo.projector.rebuild(repo.signer.deviceId)
        val toSend = eventsLackedBy(reply.versionVector)
        send(SyncMessage.Events(toSend.map { it.toWire() }))
        val done = next() as? SyncMessage.Done ?: throw IllegalStateException("Protocol error: expected DONE")
        log("In-session sync: received ${res.inserted.size}, sent ${toSend.size} (peer stored ${done.inserted})")
        return SessionSyncSummary(res.inserted, toSend.size, res.rejected).also { lastResync = it }
    }

    private suspend fun respondToSync(m: SyncMessage.SessionSync) {
        if (!busy.tryLock()) {
            runCatching { send(SyncMessage.SessionBusy("already syncing or settling")) }
            return
        }
        try {
            drain()
            val outgoing = eventsLackedBy(m.versionVector)
            send(SyncMessage.SessionSyncReply(outgoing.map { it.toWire() }, myVersionVector()))
            val ev = next() as? SyncMessage.Events ?: return
            beforePeerSync()
            val res = repo.eventLog.insertRemote(ev.events.map { it.toEntity() })
            repo.projector.rebuild(repo.signer.deviceId)
            send(SyncMessage.Done(res.inserted.size, res.rejected))
            onPeerSynced(SessionSyncSummary(res.inserted, outgoing.size, res.rejected))
        } catch (e: SessionClosed) {
            log("In-session sync interrupted: ${e.message}")
        } finally {
            busy.unlock()
        }
    }

    // ------------------------------------------------------------------ settle up

    /**
     * Proposer side. [fromUserId] pays [toUserId] [amountMinor] in [groupId] (null = overall).
     * One of them must be this device's user, the other the session peer.
     */
    suspend fun proposeSettlement(groupId: String?, fromUserId: String, toUserId: String, amountMinor: Long): SettlementOutcome {
        if (!busy.tryLock()) return SettlementOutcome.Failed("Another sync or settlement is already running")
        var settlementId = ""
        try {
            val me = repo.getProfile()?.userId ?: return SettlementOutcome.Failed("No local profile")
            val peerUser = peer.userId ?: return SettlementOutcome.Failed("Peer has no profile")
            if (setOf(fromUserId, toUserId) != setOf(me, peerUser)) return SettlementOutcome.Failed("You can only settle with the connected friend")

            // a. full sync first so both logs (and therefore balances) match
            resyncLocked()

            // local checks (same rules the peer will apply)
            repo.settlementBlocker(groupId, fromUserId, toUserId)?.let { return SettlementOutcome.Blocked(it) }
            val bal = repo.pairBalance(groupId, fromUserId, toUserId)
            if (bal.owedFromTo <= 0) return SettlementOutcome.Blocked("${repo.userName(fromUserId)} doesn't owe ${repo.userName(toUserId)} anything here")
            if (amountMinor <= 0 || amountMinor > bal.owedFromTo) return SettlementOutcome.Blocked("Amount must be between ₹0.01 and the balance")

            // b. propose
            val terms = SettlementTerms(
                settlementId = UUID.randomUUID().toString().also { settlementId = it },
                groupId = groupId, fromUserId = fromUserId, toUserId = toUserId, amountMinor = amountMinor,
                currency = DEFAULT_CURRENCY, balanceHash = bal.hash,
                proposerUserId = me, accepterUserId = peerUser, proposedAt = System.currentTimeMillis(),
            )
            val mySig = Settlement.sign(repo.signer, terms)
            send(SyncMessage.SettlePropose(terms, repo.signer.publicKeyB64, mySig))
            log("→ SETTLE_PROPOSE ${repo.userName(fromUserId)} → ${repo.userName(toUserId)} ${terms.amountMinor} (hash ${bal.hash.take(8)})")

            // c. wait for the peer's validation + its user's decision
            val accept = when (val m = next(promptTimeoutMs + stepTimeoutMs)) {
                is SyncMessage.SettleAccept -> m
                is SyncMessage.SettleReject -> {
                    log("← SETTLE_REJECT: ${m.reason}")
                    return if (m.reason.startsWith(DECLINED)) SettlementOutcome.Declined(m.reason) else SettlementOutcome.Blocked(m.reason)
                }
                else -> return SettlementOutcome.Failed("Protocol error: unexpected ${m::class.simpleName}")
            }
            if (accept.settlementId != terms.settlementId ||
                CryptoUtil.deviceIdFor(accept.accepterPublicKey) != peer.deviceId ||
                !Settlement.verifyTermsSignature(accept.accepterPublicKey, terms, accept.accepterSignature)
            ) {
                runCatching { send(SyncMessage.SettleAbort(terms.settlementId, "Invalid co-signature")) }
                return SettlementOutcome.Failed("Peer's co-signature is invalid")
            }
            log("← SETTLE_ACCEPT (co-signature verified)")

            // d. one event, both signatures; held in memory until the peer acknowledges it
            val proof = SettlementProof(terms, repo.signer.publicKeyB64, mySig, accept.accepterPublicKey, accept.accepterSignature)
            val pending = repo.buildSettlementEvent(proof)
            send(SyncMessage.SettleFinal(terms.settlementId, pending.toWire()))
            log("→ SETTLE_FINAL (event ${pending.id.take(8)})")
            when (val m = next()) {
                is SyncMessage.SettleAck -> Unit
                is SyncMessage.SettleAbort -> return SettlementOutcome.Failed("Peer aborted: ${m.reason}")
                else -> return SettlementOutcome.Failed("Protocol error: unexpected ${m::class.simpleName}")
            }
            if (!repo.commitSettlementEvent(pending)) {
                runCatching { send(SyncMessage.SettleAbort(terms.settlementId, "Proposer could not commit")) }
                return SettlementOutcome.Failed("Could not commit the settlement locally; nothing recorded")
            }
            send(SyncMessage.SettleCommit(terms.settlementId))
            log("→ SETTLE_COMMIT: settlement recorded")
            return SettlementOutcome.Settled(terms.settlementId, terms)
        } catch (e: SessionClosed) {
            log("Settlement discarded: ${e.message}")
            return SettlementOutcome.Dropped("Connection dropped during settle-up (${e.message}). Nothing was recorded on either phone.")
        } catch (e: IllegalStateException) {
            return SettlementOutcome.Failed(e.message ?: "Settlement failed")
        } finally {
            busy.unlock()
        }
    }

    private suspend fun respondToProposal(m: SyncMessage.SettlePropose) {
        val t = m.terms
        if (!busy.tryLock()) {
            runCatching { send(SyncMessage.SettleReject(t.settlementId, "Peer is busy with another sync or settlement")) }
            return
        }
        try {
            onIncomingOutcome(respondLocked(m))
        } finally {
            busy.unlock()
        }
    }

    private suspend fun respondLocked(m: SyncMessage.SettlePropose): SettlementOutcome {
        val t = m.terms
        suspend fun reject(reason: String, outcome: SettlementOutcome = SettlementOutcome.Blocked(reason)): SettlementOutcome {
            runCatching { send(SyncMessage.SettleReject(t.settlementId, reason)) }
            log("→ SETTLE_REJECT: $reason")
            return outcome
        }
        try {
            drain()
            val me = repo.getProfile()?.userId ?: return reject("Peer has no profile")
            if (t.proposerUserId != peer.userId || t.accepterUserId != me ||
                CryptoUtil.deviceIdFor(m.proposerPublicKey) != peer.deviceId ||
                !Settlement.verifyTermsSignature(m.proposerPublicKey, t, m.proposerSignature)
            ) return reject("Proposal is not validly signed by the connected device")
            repo.validateSettlementTerms(t)?.let { return reject(it) }
            log("← SETTLE_PROPOSE validated (balance hash matches)")

            val yes = try {
                withTimeout(promptTimeoutMs) { confirmIncoming(t) }
            } catch (e: TimeoutCancellationException) {
                false
            }
            if (!yes) return reject("$DECLINED by ${repo.userName(me)}", SettlementOutcome.Declined("You declined"))
            // Re-check: nothing may have changed while the dialog was open.
            repo.validateSettlementTerms(t)?.let { return reject(it) }

            val mySig = Settlement.sign(repo.signer, t)
            send(SyncMessage.SettleAccept(t.settlementId, repo.signer.publicKeyB64, mySig))
            log("→ SETTLE_ACCEPT (co-signed)")

            val fin = when (val n = next()) {
                is SyncMessage.SettleFinal -> n
                is SyncMessage.SettleAbort -> return SettlementOutcome.Failed("Peer aborted: ${n.reason}")
                else -> return SettlementOutcome.Failed("Protocol error: unexpected ${n::class.simpleName}")
            }
            val ev = fin.event.toEntity()
            val payload = runCatching { FsJson.decodeFromString<PaymentPayload>(ev.payload) }.getOrNull()
            val ok = payload != null && EventLog.verify(ev) && ev.deviceId == peer.deviceId &&
                payload.settlement?.terms == t && payload.settlement.accepterSignature == mySig &&
                Settlement.proofProblem(ev, payload) == null
            if (!ok) {
                runCatching { send(SyncMessage.SettleAbort(t.settlementId, "Final event does not match the accepted terms")) }
                return SettlementOutcome.Failed("Peer's final settlement event was invalid; nothing recorded")
            }
            send(SyncMessage.SettleAck(t.settlementId))
            when (val n = next()) {
                is SyncMessage.SettleCommit -> Unit
                is SyncMessage.SettleAbort -> return SettlementOutcome.Failed("Peer aborted: ${n.reason}")
                else -> return SettlementOutcome.Failed("Protocol error: unexpected ${n::class.simpleName}")
            }
            if (!repo.commitSettlementEvent(ev)) return SettlementOutcome.Failed("Could not store the settlement event")
            log("← SETTLE_COMMIT: settlement recorded")
            return SettlementOutcome.Settled(t.settlementId, t)
        } catch (e: SessionClosed) {
            log("Settlement discarded: ${e.message}")
            return SettlementOutcome.Dropped("Connection dropped during settle-up (${e.message}). Nothing was recorded.")
        }
    }

    companion object {
        const val DECLINED = "Declined"
    }
}
