package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.domain.Money
import com.rupeewise.sanitysnap.sync.SessionUi
import com.rupeewise.sanitysnap.sync.SettleOption
import com.rupeewise.sanitysnap.sync.SimulatedPeer
import com.rupeewise.sanitysnap.sync.SyncMode
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.OpenConflictsDialog
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.launch

/**
 * "Sync now". Real radios are stubbed (TODO(nearby)); the protocol runs for real against
 * in-app simulated peer devices (separate DB + key) over an in-memory channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(adoptMode: Boolean, onBack: () -> Unit, onFinishedOnboarding: () -> Unit, openConflicts: () -> Unit = {}, openHistory: () -> Unit = {}) {
    val container = LocalContainer.current
    val repo = container.repo
    val ctrl = container.sync
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by ctrl.state.collectAsStateWithLifecycle()
    val peers by ctrl.peers.collectAsStateWithLifecycle()
    val profile by repo.profile.collectAsStateWithLifecycle(null)
    val myEvents by repo.eventCount.collectAsStateWithLifecycle(0)
    var challengeFor by remember { mutableStateOf<String?>(null) }
    var showSettle by remember { mutableStateOf(false) }
    val openConflictCount by repo.openConflictCount.collectAsStateWithLifecycle(0)
    val conflictPrompt by ctrl.syncConflictPrompt.collectAsStateWithLifecycle()

    // A live session only exists while this screen is open (both phones on the Sync screen).
    DisposableEffect(Unit) { onDispose { ctrl.disconnect() } }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); ctrl.clearMessage() }
    }

    FsScaffold(title = if (adoptMode) "Sync with my other device" else "Sync now", onBack = onBack, snackbar = snackbar) {
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        SectionCard("Nearby devices") {
            Text("Transport: ${container.nearby.name}")
            Text(
                "Real proximity sync (Nearby Connections / Wi-Fi Direct / hotspot) is stubbed in V1. " +
                    "Use the simulated peer phones below — they run the exact same signed event-log protocol.",
                style = MaterialTheme.typography.bodySmall,
            )
            // TODO(nearby): list discovered peers from container.nearby.discoverPeers() and call
            //  SyncEngine.runSession(container.nearby.connect(peer), ...) — same flow as below.
        }

        SectionCard("This phone") {
            Text(profile?.let { "@${it.username} • device ${it.deviceId}" } ?: "Fresh install (no profile yet)")
            Text("$myEvents event(s) in log", style = MaterialTheme.typography.bodySmall)
            if (adoptMode && profile != null) {
                Button(onClick = onFinishedOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Account restored — continue") }
            }
        }

        state.session?.let { session ->
            LiveSessionCard(session, state.running, onSettle = { showSettle = true })
        }

        peers.forEach { peer ->
            key(peer) { PeerCard(peer, adoptMode && profile == null, state.running, connectedHere = state.session?.connected == true && state.session?.peerLabel == peer.label) }
        }

        if (!adoptMode && peers.size >= 2) {
            OutlinedButton(enabled = !state.running, onClick = { ctrl.gossipPeers(peers[0], peers[1]) }, modifier = Modifier.fillMaxWidth()) {
                Text("Gossip ${peers[0].label} ⇄ ${peers[1].label} (transitive demo)")
            }
        }
        TextButton(onClick = { ctrl.resetPeers() }) { Text("Wipe simulated peers") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.lastSummary != null) OutlinedButton(onClick = { ctrl.showLastSummary() }) { Text("Last sync summary") }
            OutlinedButton(onClick = openHistory) { Text("Sync history") }
        }
        if (state.log.isNotEmpty()) {
            SectionCard("Protocol log") {
                state.log.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
        SectionCard("How sync works") {
            Text(
                "1 HELLO: exchange identity + version vectors\n" +
                    "2 CONFIRM: both people confirm the peer fingerprint\n" +
                    "3 EVENTS: send every signed event the peer lacks (incl. events from other devices → transitive)\n" +
                    "4 VERIFY: check signatures and key↔device binding\n" +
                    "5 MERGE: replay the merged log; divergent edits become conflicts\n" +
                    "6 SUMMARY: review changes; challenge anything that looks wrong\n" +
                    "7 LIVE SESSION: stay connected to settle up; both phones co-sign one payment event",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    state.pendingConfirm?.let { peer ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Confirm peer") },
            text = {
                Text(
                    "Sync with @${peer.username ?: "unknown"}?\nDevice ${peer.deviceId}\nKey fingerprint ${peer.keyFingerprint}\n" +
                        "${peer.eventCount} event(s) on their side.\n\nCheck the fingerprint matches what their screen shows.",
                )
            },
            confirmButton = { TextButton(onClick = { ctrl.respondToConfirm(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { ctrl.respondToConfirm(false) }) { Text("Reject") } },
        )
    }

    challengeFor?.let { expenseId ->
        ChallengeDialog(onDismiss = { challengeFor = null }) { reason ->
            scope.launch {
                repo.raiseChallenge(expenseId, reason)
                challengeFor = null
                snackbar.showSnackbar("Challenge raised")
            }
        }
    }

    val session = state.session
    if (showSettle && session != null && session.connected) {
        SettleUpDialog(session, onDismiss = { showSettle = false }) { option, amount ->
            showSettle = false
            ctrl.settleUp(option.groupId, option.owedByMe, amount)
        }
    }

    state.incomingProposal?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Confirm settle-up with @${session?.peerUsername ?: "friend"}") },
            text = { Text(p.text + "\n\nAccepting co-signs one payment that is recorded on both phones at the same time.") },
            confirmButton = { TextButton(onClick = { ctrl.respondToIncoming(true) }) { Text("Accept & sign") } },
            dismissButton = { TextButton(onClick = { ctrl.respondToIncoming(false) }) { Text("Decline") } },
        )
    }

    state.peerPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("[Simulated ${session?.peerLabel ?: "peer"}] Settle-up request") },
            text = { Text("This dialog is what the other phone's user sees.\n\n" + p.text) },
            confirmButton = { TextButton(onClick = { ctrl.respondAsPeer(true) }) { Text("Accept as peer") } },
            dismissButton = { TextButton(onClick = { ctrl.respondAsPeer(false) }) { Text("Decline as peer") } },
        )
    }

    val lastSummary = state.lastSummary
    if (state.showSummary && lastSummary != null) {
        ModalBottomSheet(onDismissRequest = { ctrl.dismissSummary() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("What changed in this sync", style = MaterialTheme.typography.titleLarge)
                SyncSummaryContent(lastSummary, onOpenConflicts = { ctrl.dismissSummary(); ctrl.dismissSyncConflictPrompt(); openConflicts() })
                TextButton(onClick = { ctrl.dismissSummary() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }

    if (!state.showSummary && conflictPrompt && openConflictCount > 0 && state.pendingConfirm == null && state.incomingProposal == null && state.peerPrompt == null) {
        OpenConflictsDialog(
            openConflictCount,
            onResolve = { ctrl.dismissSyncConflictPrompt(); openConflicts() },
            onLater = { ctrl.dismissSyncConflictPrompt() },
        )
    }
}

@Composable
private fun LiveSessionCard(session: SessionUi, running: Boolean, onSettle: () -> Unit) {
    val ctrl = LocalContainer.current.sync
    SectionCard(if (session.connected) "Live session: @${session.peerUsername} (${session.peerLabel})" else "Session ended") {
        if (session.connected) {
            Text("Connected. Both phones are in sync; settling up is possible while you stay on this screen.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running && !session.settling, onClick = onSettle, modifier = Modifier.fillMaxWidth()) {
                Text("Settle up with @${session.peerUsername}")
            }
            if (session.settling) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !running && !session.settling, onClick = { ctrl.syncAgain() }, modifier = Modifier.weight(1f)) { Text("Sync again") }
                OutlinedButton(onClick = { ctrl.disconnect() }, modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }
            OutlinedButton(enabled = !running && !session.settling, onClick = { ctrl.peerProposesSettlement() }, modifier = Modifier.fillMaxWidth()) {
                Text("Simulate: @${session.peerUsername} proposes settling up")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = session.dropBeforeFinal, onCheckedChange = { ctrl.setDropBeforeFinal(it) })
                Text("  Simulate connection drop before the final step", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("Disconnected. Sync again to reconnect before settling up.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettleUpDialog(session: SessionUi, onDismiss: () -> Unit, onPropose: (SettleOption, Long) -> Unit) {
    val ctrl = LocalContainer.current.sync
    val options by produceState<List<SettleOption>?>(null, session) { value = ctrl.settleOptions() }
    var selected by remember { mutableStateOf<SettleOption?>(null) }
    var amount by remember { mutableStateOf("") }
    LaunchedEffect(options) {
        val first = options?.firstOrNull { it.owedByMe != 0L }
        if (selected == null && first != null) { selected = first; amount = Money.plain(kotlin.math.abs(first.owedByMe)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle up with @${session.peerUsername}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = options
                if (opts == null) Text("Computing balances…")
                else if (opts.all { it.owedByMe == 0L }) Text("You and @${session.peerUsername} are already settled up.")
                else opts.filter { it.owedByMe != 0L }.forEach { o ->
                    FilterChip(
                        selected = selected == o,
                        onClick = { selected = o; amount = Money.plain(kotlin.math.abs(o.owedByMe)) },
                        label = { Text("${o.label}: " + if (o.owedByMe > 0) "you owe ${Money.format(o.owedByMe)}" else "owes you ${Money.format(-o.owedByMe)}") },
                    )
                }
                selected?.let { o ->
                    Text(if (o.owedByMe > 0) "You pay @${session.peerUsername}" else "@${session.peerUsername} pays you")
                    OutlinedTextField(amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Both phones sync first and compare a hash of this balance. @${session.peerUsername} must confirm on their phone; " +
                            "the payment is recorded on both phones at once, or not at all.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = {
                val o = selected ?: return@TextButton
                val minor = Money.parseToMinor(amount) ?: return@TextButton
                onPropose(o, minor)
            }) { Text("Propose") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PeerCard(peer: SimulatedPeer, adoptFlow: Boolean, running: Boolean, connectedHere: Boolean) {
    val ctrl = LocalContainer.current.sync
    val peerProfile by peer.repo.profile.collectAsStateWithLifecycle(null)
    val peerEvents by peer.repo.eventCount.collectAsStateWithLifecycle(0)
    val peerConflicts by peer.repo.openConflictCount.collectAsStateWithLifecycle(0)
    var username by remember { mutableStateOf(if (peer.label.endsWith("B")) "riya" else "arjun") }

    SectionCard("Simulated ${peer.label}") {
        Text(
            (peerProfile?.let { "@${it.username} • device ${it.deviceId}" } ?: "No profile") +
                " • $peerEvents event(s)" + if (peerConflicts > 0) " • $peerConflicts conflict(s)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (peerProfile == null) {
            OutlinedTextField(
                username, { username = it.trim() },
                label = { Text(if (adoptFlow) "Your existing username" else "Peer username") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (adoptFlow) {
                Button(onClick = { ctrl.seedPeerAsOwnOldDevice(peer, username) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Make this my old phone (with demo data)")
                }
            } else {
                Button(onClick = { ctrl.createPeerProfile(peer, username) }, modifier = Modifier.fillMaxWidth()) { Text("Create peer profile") }
            }
        } else if (adoptFlow) {
            Button(enabled = !running, onClick = { ctrl.syncWithPeer(peer, SyncMode.ADOPT_PEER_IDENTITY) }, modifier = Modifier.fillMaxWidth()) {
                Text("Sync & restore my account from ${peer.label}")
            }
        } else {
            if (connectedHere) {
                Button(enabled = !running, onClick = { ctrl.syncAgain() }, modifier = Modifier.fillMaxWidth()) { Text("Sync again (connected)") }
            } else {
                Button(enabled = !running, onClick = { ctrl.syncWithPeer(peer) }, modifier = Modifier.fillMaxWidth()) { Text("Connect & sync with ${peer.label}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctrl.peerAddsExpense(peer) }, modifier = Modifier.weight(1f)) { Text("Peer adds expense") }
                OutlinedButton(onClick = { ctrl.simulateDivergentEdit(peer) }, modifier = Modifier.weight(1f)) { Text("Divergent edit") }
            }
        }
    }
}
