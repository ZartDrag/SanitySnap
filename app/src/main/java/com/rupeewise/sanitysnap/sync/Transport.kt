package com.rupeewise.sanitysnap.sync

import kotlinx.coroutines.channels.Channel

/**
 * A bidirectional, ordered, reliable message pipe between two devices for one sync session.
 * Messages are opaque JSON strings (see [SyncMessage]).
 */
interface SyncChannel {
    suspend fun send(message: String)
    suspend fun receive(): String
    fun close()
}

data class DiscoveredPeer(val endpointId: String, val displayName: String)

/**
 * Proximity transport abstraction (Nearby Connections / Wi-Fi Direct / local hotspot socket).
 * The [SyncEngine] only depends on [SyncChannel], so any transport can be plugged in.
 */
interface NearbyTransport {
    val name: String
    fun isAvailable(): Boolean
    suspend fun startAdvertising(localDisplayName: String)
    suspend fun discoverPeers(): List<DiscoveredPeer>
    suspend fun connect(peer: DiscoveredPeer): SyncChannel
    suspend fun stop()
}

/**
 * Placeholder for the real proximity transport.
 *
 * TODO(nearby): implement with Google Nearby Connections (play-services-nearby,
 *  Strategy.P2P_POINT_TO_POINT): startAdvertising/startDiscovery with a service id like
 *  "com.rupeewise.sanitysnap.sync", show the 4-digit authentication token on BOTH phones for peer
 *  confirmation, then wrap Payload.fromBytes()/PayloadCallback into a [SyncChannel].
 * TODO(nearby): alternative without Play Services — Wi-Fi Direct (WifiP2pManager) group +
 *  plain TCP socket, or a local-hotspot ServerSocket with a QR code carrying ip:port + pubkey.
 * TODO(nearby): request runtime permissions (BLUETOOTH_*, NEARBY_WIFI_DEVICES, location <= 32).
 * TODO(nearby): keep the connection open after SyncEngine.runSession(keepOpen = true) and hand the
 *  same SyncChannel to LiveSession; it carries the session_sync / settle_* / bye messages
 *  (SyncMessage) unchanged. Map onDisconnected() to closing the channel so receive() throws: a
 *  LiveSession then discards any half-finished settlement.
 */
class NoOpNearbyTransport : NearbyTransport {
    override val name = "Nearby (not implemented in V1)"
    override fun isAvailable() = false
    override suspend fun startAdvertising(localDisplayName: String) = Unit
    override suspend fun discoverPeers(): List<DiscoveredPeer> = emptyList()
    override suspend fun connect(peer: DiscoveredPeer): SyncChannel =
        throw UnsupportedOperationException("Nearby transport is stubbed in V1 — use the in-app simulated peer")
    override suspend fun stop() = Unit
}

/** In-process transport used for the simulated peer devices and unit tests. */
class InMemoryChannel(
    private val outbox: Channel<String>,
    private val inbox: Channel<String>,
) : SyncChannel {
    override suspend fun send(message: String) = outbox.send(message)
    override suspend fun receive(): String = inbox.receive()
    override fun close() {
        outbox.close()
    }

    companion object {
        /** Returns two connected ends (A, B). */
        fun pair(): Pair<InMemoryChannel, InMemoryChannel> {
            val aToB = Channel<String>(Channel.UNLIMITED)
            val bToA = Channel<String>(Channel.UNLIMITED)
            return InMemoryChannel(aToB, bToA) to InMemoryChannel(bToA, aToB)
        }
    }
}

/**
 * Debug/test wrapper that simulates the radio link dying. While [armed], the first time either end
 * tries to send a message of type [dropOnType] (e.g. "settle_final"), BOTH directions are closed
 * and the send fails, exactly like a Nearby disconnect in the middle of a session.
 */
class SimulatedLink(private val dropOnType: String = "settle_final") {
    @Volatile var armed: Boolean = false
    private val raw = InMemoryChannel.pair()

    private inner class End(private val inner: InMemoryChannel) : SyncChannel {
        override suspend fun send(message: String) {
            if (armed && message.contains("\"type\":\"$dropOnType\"")) {
                armed = false
                raw.first.close(); raw.second.close()
                throw java.io.IOException("Simulated connection drop")
            }
            inner.send(message)
        }
        override suspend fun receive(): String = inner.receive()
        override fun close() = inner.close()
    }

    val a: SyncChannel = End(raw.first)
    val b: SyncChannel = End(raw.second)
}
