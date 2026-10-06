package com.rupeewise.sanitysnap.sync

import android.content.Context
import com.rupeewise.sanitysnap.crypto.SoftwareSigner
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.repo.ActionResult
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.data.repo.SyncHistoryStore
import com.rupeewise.sanitysnap.data.repo.SyncOutcome
import com.rupeewise.sanitysnap.data.repo.SyncTransport
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * An in-app simulated "other phone" with its own Room DB and its own device key. Used to
 * exercise the real sync protocol end-to-end without radios.
 * TODO(nearby): once a real transport exists this stays as a debug-only tool.
 */
class SimulatedPeer(val label: String, private val context: Context, private val key: String) {
    private val dbName = "fairshare_peer_$key.db"
    private val prefs = "fairshare_peer_key_$key"
    val db: FairShareDatabase = FairShareDatabase.build(context, dbName)
    val repo = FairShareRepository(db, SoftwareSigner.loadOrCreate(context, prefs))
    val engine = SyncEngine(repo)

    fun destroy() {
        db.close()
        context.deleteDatabase(dbName)
        SoftwareSigner.reset(context, prefs)
    }
}

data class SyncUiState(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    /** Summary of the most recent sync run (also persisted in sync history). */
    val lastSummary: SyncSummary? = null,
    val lastHistoryId: String? = null,
    /** Show the post-sync summary bottom sheet. */
    val showSummary: Boolean = false,
    val message: String? = null,
    val pendingConfirm: PeerInfo? = null,
    /** Live session (stays connected after a successful sync until Disconnect / leaving Sync). */
    val session: SessionUi? = null,
    /** A connected friend proposed a settlement to THIS phone; the user must accept or decline. */
    val incomingProposal: ProposalUi? = null,
    /** The simulated peer device's user is being asked (shown on this phone, labelled as the peer). */
    val peerPrompt: ProposalUi? = null,
)

data class SessionUi(
    val peerLabel: String,
    val peerUserId: String,
    val peerUsername: String,
    val connected: Boolean,
    val settling: Boolean = false,
    /** Debug: simulate the link dying right before the final settlement step. */
    val dropBeforeFinal: Boolean = false,
)

data class ProposalUi(val terms: com.rupeewise.sanitysnap.domain.SettlementTerms, val text: String)

/** One option in the "Settle up with @x" dialog. owedByMe > 0: I owe them; < 0: they owe me. */
data class SettleOption(val groupId: String?, val label: String, val owedByMe: Long)

/** Orchestrates sync sessions for the UI and owns the simulated peers. */
class SyncController(
    private val context: Context,
    private val local: FairShareRepository,
    private val scope: CoroutineScope,
) {
    private val localEngine = SyncEngine(local)
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _peers = MutableStateFlow(createPeers())
    val peers: StateFlow<List<SimulatedPeer>> = _peers.asStateFlow()

    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    private var incomingDeferred: CompletableDeferred<Boolean>? = null
    private var peerPromptDeferred: CompletableDeferred<Boolean>? = null

    private var localSession: LiveSession? = null
    private var peerSession: LiveSession? = null
    private var sessionPeer: SimulatedPeer? = null
    private var link: SimulatedLink? = null
    private var sessionId: String = ""
    private var peerSyncBefore: ChangeSummary.Before? = null

    /** Local-only sync history (not synced, not in backups). */
    val history = SyncHistoryStore(local.db)

    fun dismissSummary() = _state.update { it.copy(showSummary = false) }
    fun showLastSummary() = _state.update { it.copy(showSummary = it.lastSummary != null) }

    /** Builds + persists the summary of one sync run and opens the summary sheet. */
    private suspend fun recordRun(
        peerInfo: PeerInfo?,
        outcome: String,
        message: String?,
        sent: Int,
        newEvents: List<com.rupeewise.sanitysnap.data.db.EventEntity>,
        before: ChangeSummary.Before?,
        show: Boolean = true,
    ) {
        val peerName = peerInfo?.username?.let { "@$it" } ?: "peer"
        val summary = if (before != null) ChangeSummary.build(local, before, newEvents, peerInfo?.deviceId, peerName)
        else SyncSummary(peerName, peerInfo?.deviceId?.take(6) ?: "")
        val id = history.record(
            sessionId, peerInfo?.userId, peerInfo?.username, peerInfo?.deviceId, SyncTransport.SIMULATED,
            outcome, message, sent, newEvents.map { it.id }, summary,
        )
        _state.update { it.copy(lastSummary = summary, lastHistoryId = id, showSummary = show && outcome == SyncOutcome.OK) }
    }

    private suspend fun recordSettlement(o: SettlementOutcome) {
        val rec = when (o) {
            is SettlementOutcome.Settled -> {
                val t = o.terms
                val me = local.getProfile()?.userId
                fun n(id: String, nm: String) = if (id == me) "You" else nm
                val scopeText = t.groupId?.let { gid -> local.db.groupDao().byId(gid)?.name?.let { "in \u201C$it\u201D" } } ?: "overall"
                SettlementRecord(SyncOutcome.OK, "${n(t.fromUserId, local.userName(t.fromUserId))} paid ${n(t.toUserId, local.userName(t.toUserId))} ${Money.format(t.amountMinor, t.currency)} $scopeText (co-signed)", o.paymentId)
            }
            is SettlementOutcome.Dropped -> SettlementRecord(SyncOutcome.DROPPED, o.reason)
            is SettlementOutcome.Blocked -> SettlementRecord("BLOCKED", o.reason)
            is SettlementOutcome.Declined -> SettlementRecord("DECLINED", o.reason)
            is SettlementOutcome.Failed -> SettlementRecord(SyncOutcome.FAILED, o.reason)
        }
        history.addSettlement(sessionId, rec)
    }

    /**
     * "Conflicts need a decision" reminders. Home's flag starts true (= app open); both flags are
     * raised again after every completed sync and cleared when the user taps Resolve or Later.
     */
    private val _homeConflictPrompt = MutableStateFlow(true)
    val homeConflictPrompt: StateFlow<Boolean> = _homeConflictPrompt.asStateFlow()
    private val _syncConflictPrompt = MutableStateFlow(false)
    val syncConflictPrompt: StateFlow<Boolean> = _syncConflictPrompt.asStateFlow()

    fun dismissHomeConflictPrompt() { _homeConflictPrompt.value = false }
    fun dismissSyncConflictPrompt() { _syncConflictPrompt.value = false }
    private fun syncCompleted() {
        _homeConflictPrompt.value = true
        _syncConflictPrompt.value = true
    }

    private fun createPeers() = listOf(
        SimulatedPeer("Device B", context, "b"),
        SimulatedPeer("Device C", context, "c"),
    )

    private fun log(line: String) = _state.update { it.copy(log = it.log + line) }
    private fun message(m: String) = _state.update { it.copy(message = m) }

    fun respondToConfirm(accept: Boolean) {
        _state.update { it.copy(pendingConfirm = null) }
        confirmDeferred?.complete(accept)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Runs the real protocol between this phone and a simulated peer over an in-memory pipe and,
     * if it succeeds, keeps the link open as a live session (needed for settle-up).
     */
    fun syncWithPeer(peer: SimulatedPeer, mode: SyncMode = SyncMode.NORMAL) = scope.launch {
        if (_state.value.running) return@launch
        if (peer.repo.getProfile() == null) {
            message("${peer.label} has no profile yet — create one first")
            return@launch
        }
        closeSession()
        sessionId = java.util.UUID.randomUUID().toString()
        _state.update { SyncUiState(running = true, log = listOf("=== Sync this phone ⇄ ${peer.label} (${mode.name}) ===")) }
        val before = if (local.getProfile() != null) ChangeSummary.capture(local) else null
        var peerInfoSeen: PeerInfo? = null
        val keepOpen = mode == SyncMode.NORMAL
        val l = SimulatedLink()
        try {
            val peerJob = async {
                peer.engine.runSession(l.b, SyncMode.NORMAL, confirmPeer = { true }, keepOpen = keepOpen) // simulated user taps "Accept"
            }
            val result = localEngine.runSession(
                l.a, mode,
                confirmPeer = { info ->
                    val d = CompletableDeferred<Boolean>()
                    confirmDeferred = d
                    _state.update { it.copy(pendingConfirm = info) }
                    d.await()
                },
                log = ::log,
                keepOpen = keepOpen,
            )
            val peerResult = peerJob.await()
            peerInfoSeen = result.peer
            if (result.aborted != null) {
                log("ABORTED: ${result.aborted}")
                message(result.aborted)
                l.a.close(); l.b.close()
                recordRun(result.peer, SyncOutcome.FAILED, result.aborted, 0, emptyList(), null)
            } else {
                recordRun(result.peer, SyncOutcome.OK, null, result.sent, result.newEvents, before ?: ChangeSummary.capture(local))
                message("Synced: received ${result.newEvents.size} change(s), sent ${result.sent} (peer stored ${peerResult.newEvents.size})")
                val me = local.getProfile()
                val p = result.peer
                if (keepOpen && p?.userId != null && peerResult.peer != null && me != null && p.userId != me.userId) {
                    openSession(peer, l, p, peerResult.peer)
                } else {
                    l.a.close(); l.b.close()
                }
                syncCompleted()
            }
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            message("Sync failed: ${e.message}")
            runCatching { recordRun(peerInfoSeen, SyncOutcome.FAILED, e.message, 0, emptyList(), null) }
        } finally {
            _state.update { it.copy(running = false, pendingConfirm = null) }
        }
    }

    private fun openSession(peer: SimulatedPeer, l: SimulatedLink, peerInfo: PeerInfo, meAsSeenByPeer: PeerInfo) {
        val ls = LiveSession(
            local, l.a, peerInfo, scope,
            confirmIncoming = { t -> askUser(t) },
            log = ::log,
            onPeerSynced = { s ->
                scope.launch {
                    if (s.newEvents.isNotEmpty()) recordRun(peerInfo, SyncOutcome.OK, "Sync driven by the peer", s.sent, s.newEvents, peerSyncBefore)
                    syncCompleted()
                }
            },
            beforePeerSync = { peerSyncBefore = ChangeSummary.capture(local) },
            onIncomingOutcome = { o -> scope.launch { recordSettlement(o); message(describe(o, incoming = true)) } },
        )
        val ps = LiveSession(
            peer.repo, l.b, meAsSeenByPeer, scope,
            confirmIncoming = { t -> askSimulatedPeer(peer, t) },
            log = { log("[${peer.label}] $it") },
        )
        ls.onClosed = { reason ->
            _state.update { st -> st.copy(session = st.session?.copy(connected = false, settling = false), incomingProposal = null, peerPrompt = null) }
            incomingDeferred?.complete(false); peerPromptDeferred?.complete(false)
            if (reason != "Disconnected") message("Live session with ${peer.label} ended: $reason")
        }
        localSession = ls.start()
        peerSession = ps.start()
        sessionPeer = peer
        link = l
        _state.update {
            it.copy(session = SessionUi(peer.label, peerInfo.userId!!, peerInfo.username ?: "?", connected = true))
        }
        log("Live session open with @${peerInfo.username} — you can settle up now")
    }

    private suspend fun describeTerms(repo: FairShareRepository, t: com.rupeewise.sanitysnap.domain.SettlementTerms, viewer: String?): String {
        fun n(id: String, name: String) = if (id == viewer) "You" else name
        val from = n(t.fromUserId, repo.userName(t.fromUserId))
        val to = n(t.toUserId, repo.userName(t.toUserId)).let { if (it == "You") "you" else it }
        val scopeText = t.groupId?.let { gid -> repo.db.groupDao().byId(gid)?.name?.let { "in group \"$it\"" } } ?: "overall (all groups + non-group)"
        return "$from ${if (from == "You") "pay" else "pays"} $to ${Money.format(t.amountMinor, t.currency)} $scopeText.\n" +
            "Proposed by ${repo.userName(t.proposerUserId)}. Balance hash ${t.balanceHash.take(8)} matches this phone."
    }

    /** Incoming proposal to THIS phone's user. */
    private suspend fun askUser(t: com.rupeewise.sanitysnap.domain.SettlementTerms): Boolean {
        val d = CompletableDeferred<Boolean>()
        incomingDeferred = d
        _state.update { it.copy(incomingProposal = ProposalUi(t, describeTerms(local, t, local.getProfile()?.userId))) }
        return try { d.await() } finally { _state.update { it.copy(incomingProposal = null) } }
    }

    /** The simulated peer's user is asked; the dialog is shown on this phone, labelled as the peer. */
    private suspend fun askSimulatedPeer(peer: SimulatedPeer, t: com.rupeewise.sanitysnap.domain.SettlementTerms): Boolean {
        val d = CompletableDeferred<Boolean>()
        peerPromptDeferred = d
        _state.update { it.copy(peerPrompt = ProposalUi(t, describeTerms(peer.repo, t, peer.repo.getProfile()?.userId))) }
        return try { d.await() } finally { _state.update { it.copy(peerPrompt = null) } }
    }

    fun respondToIncoming(accept: Boolean) { incomingDeferred?.complete(accept) }
    fun respondAsPeer(accept: Boolean) { peerPromptDeferred?.complete(accept) }

    fun setDropBeforeFinal(on: Boolean) {
        link?.armed = on
        _state.update { it.copy(session = it.session?.copy(dropBeforeFinal = on)) }
    }

    private fun describe(o: SettlementOutcome, incoming: Boolean): String = when (o) {
        is SettlementOutcome.Settled -> "Settled up: ${Money.format(o.terms.amountMinor, o.terms.currency)} recorded on both phones"
        is SettlementOutcome.Blocked -> o.reason
        is SettlementOutcome.Declined -> if (incoming) "You declined the settlement" else "The other person declined: ${o.reason}"
        is SettlementOutcome.Dropped -> o.reason
        is SettlementOutcome.Failed -> "Settlement failed: ${o.reason}"
    }

    /** Settle-up options with the connected friend: overall + each shared group with a balance. */
    suspend fun settleOptions(): List<SettleOption> {
        val s = _state.value.session ?: return emptyList()
        val me = local.getProfile()?.userId ?: return emptyList()
        val out = mutableListOf(SettleOption(null, "Overall (all groups + non-group)", local.pairBalance(null, me, s.peerUserId).owedFromTo))
        local.groups.first().filter { g -> local.db.groupDao().members(g.id).any { it.userId == s.peerUserId } && !g.deleted }.forEach { g ->
            val owed = local.pairBalance(g.id, me, s.peerUserId).owedFromTo
            if (owed != 0L) out += SettleOption(g.id, "Group \"${g.name}\"", owed)
        }
        return out
    }

    /** This phone proposes. Direction follows the balance: whoever owes pays. */
    fun settleUp(groupId: String?, owedByMe: Long, amountMinor: Long) = scope.launch {
        val ls = localSession?.takeIf { it.connected.value } ?: return@launch message("Not connected — sync with your friend first")
        val s = _state.value.session ?: return@launch
        val me = local.getProfile()?.userId ?: return@launch
        if (owedByMe == 0L) return@launch message("Nothing to settle")
        val (from, to) = if (owedByMe > 0) me to s.peerUserId else s.peerUserId to me
        _state.update { it.copy(session = it.session?.copy(settling = true)) }
        log("=== Settle up with @${s.peerUsername} ===")
        try {
            val before = ChangeSummary.capture(local)
            val o = ls.proposeSettlement(groupId, from, to, amountMinor)
            // the protocol ran a full sync first; record it if it brought anything in
            ls.lastResync?.takeIf { it.newEvents.isNotEmpty() }?.let { r ->
                recordRun(ls.peer, SyncOutcome.OK, "Re-sync before settle-up", r.sent, r.newEvents, before)
            }
            recordSettlement(o)
            syncCompleted()
            message(describe(o, incoming = false))
        } finally {
            _state.update { it.copy(session = it.session?.copy(settling = false, dropBeforeFinal = link?.armed == true)) }
        }
    }

    /** Simulated peer proposes to this phone (exercises this phone as the accepting side). */
    fun peerProposesSettlement() = scope.launch {
        val ps = peerSession?.takeIf { it.connected.value } ?: return@launch message("Not connected")
        val peer = sessionPeer ?: return@launch
        val peerMe = peer.repo.getProfile()?.userId ?: return@launch
        val me = local.getProfile()?.userId ?: return@launch
        val owedByPeer = peer.repo.pairBalance(null, peerMe, me).owedFromTo
        if (owedByPeer == 0L) return@launch message("${peer.label} sees nothing to settle with you")
        val (from, to) = if (owedByPeer > 0) peerMe to me else me to peerMe
        _state.update { it.copy(session = it.session?.copy(settling = true)) }
        log("=== ${peer.label} proposes settling up ===")
        try {
            val o = ps.proposeSettlement(null, from, to, kotlin.math.abs(owedByPeer))
            if (o !is SettlementOutcome.Settled) message("${peer.label}: ${describe(o, incoming = false)}")
        } finally {
            _state.update { it.copy(session = it.session?.copy(settling = false, dropBeforeFinal = link?.armed == true)) }
        }
    }

    /** In-session re-sync (keeps the session open). */
    fun syncAgain() = scope.launch {
        val ls = localSession?.takeIf { it.connected.value } ?: return@launch message("Not connected")
        _state.update { it.copy(running = true) }
        try {
            val before = ChangeSummary.capture(local)
            val r = ls.resync()
            recordRun(ls.peer, SyncOutcome.OK, "In-session sync", r.sent, r.newEvents, before)
            message("Synced: received ${r.newEvents.size} change(s), sent ${r.sent}")
            syncCompleted()
        } catch (e: Exception) {
            message("Sync failed: ${e.message}")
            runCatching { recordRun(ls.peer, if (!ls.connected.value) SyncOutcome.DROPPED else SyncOutcome.FAILED, e.message, 0, emptyList(), null) }
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    fun disconnect() = scope.launch { closeSession() }

    private suspend fun closeSession() {
        localSession?.disconnect()
        peerSession?.disconnect()
        localSession = null; peerSession = null; sessionPeer = null; link = null
        _state.update { it.copy(session = null, incomingProposal = null, peerPrompt = null) }
    }

    /** Gossip between two simulated peers (demonstrates transitive A→B→C propagation). */
    fun gossipPeers(a: SimulatedPeer, b: SimulatedPeer) = scope.launch {
        if (_state.value.running) return@launch
        if (a.repo.getProfile() == null || b.repo.getProfile() == null) {
            message("Both peers need a profile first")
            return@launch
        }
        _state.update { SyncUiState(running = true, log = listOf("=== Gossip ${a.label} ⇄ ${b.label} ===")) }
        try {
            val (ca, cb) = InMemoryChannel.pair()
            val jb = async { b.engine.runSession(cb, confirmPeer = { true }) }
            val ra = a.engine.runSession(ca, confirmPeer = { true }, log = { log("[${a.label}] $it") })
            val rb = jb.await()
            message("${a.label} got ${ra.newEvents.size} new event(s), ${b.label} got ${rb.newEvents.size}")
        } catch (e: Exception) {
            message("Gossip failed: ${e.message}")
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    fun createPeerProfile(peer: SimulatedPeer, username: String) = scope.launch {
        if (peer.repo.getProfile() != null) return@launch message("${peer.label} already has a profile")
        if (username.isBlank()) return@launch message("Enter a username for ${peer.label}")
        peer.repo.createProfile(username.trim(), username.trim())
        message("${peer.label} is now @${username.trim()}")
    }

    /** Fresh-install "existing user" demo: make Device B look like the user's old phone with data. */
    fun seedPeerAsOwnOldDevice(peer: SimulatedPeer, username: String) = scope.launch {
        if (username.isBlank()) return@launch message("Enter your existing username")
        val r = peer.repo
        if (r.getProfile() == null) r.createProfile(username.trim(), username.trim())
        val friend = (r.addFriendByUsername("flatmate_demo") as? ActionResult.Ok)?.value ?: return@launch
        val me = r.getProfile()!!.userId
        val g = r.createGroup("Flat expenses", listOf(friend))
        r.addExpense(g, "Groceries", 90_000, SplitType.EQUAL, mapOf(me to 90_000L), listOf(ShareInput(me), ShareInput(friend)))
        message("${peer.label} seeded as your old phone (@${username.trim()}) with a group + expense")
    }

    /** Peer adds an expense shared with this phone's user (paid by the peer, split equally). */
    fun peerAddsExpense(peer: SimulatedPeer) = scope.launch {
        val peerProfile = peer.repo.getProfile() ?: return@launch message("${peer.label} has no profile")
        val me = local.getProfile() ?: return@launch message("Create your profile first")
        if (peerProfile.userId == me.userId) return@launch message("${peer.label} is your own device; add expenses on this phone instead")
        val groups = peer.repo.groups.first()
        val shared = groups.firstOrNull { g -> peer.db.groupDao().members(g.id).any { it.userId == me.userId } }
        val groupId = shared?.id ?: peer.repo.createGroup("Trip with @${me.username}", listOf(me.userId))
        val amount = (Random.nextInt(2, 40) * 5_000).toLong()
        val desc = listOf("Cab", "Dinner", "Snacks", "Tickets", "Fuel", "Coffee").random()
        peer.repo.addExpense(
            groupId, desc, amount, SplitType.EQUAL,
            mapOf(peerProfile.userId to amount), listOf(ShareInput(peerProfile.userId), ShareInput(me.userId)),
        )
        message("${peer.label} added \"$desc\" — tap Sync to receive it")
    }

    /** Edits the same expense differently on both sides to produce a git-style conflict. */
    fun simulateDivergentEdit(peer: SimulatedPeer) = scope.launch {
        val localIds = local.db.expenseDao().allActive().sortedByDescending { it.createdAt }.map { it.id }
        val id = localIds.firstOrNull { peer.db.expenseDao().byId(it)?.deleted == false }
            ?: return@launch message("No expense exists on both devices yet — sync first")
        val mine = local.expenseSnapshot(id)!!
        val theirs = peer.repo.expenseSnapshot(id)!!
        local.updateExpenseSnapshot(mine.copy(description = mine.description.substringBefore(" [") + " [edited on this phone]"))
        peer.repo.updateExpenseSnapshot(theirs.copy(description = theirs.description.substringBefore(" [") + " [edited on ${peer.label}]"))
        message("Edited \"${mine.description}\" on both devices — sync to see the conflict")
    }

    fun resetPeers() = scope.launch {
        closeSession()
        _peers.value.forEach { it.destroy() }
        _peers.value = createPeers()
        _state.value = SyncUiState(message = "Simulated peers wiped")
    }
}
