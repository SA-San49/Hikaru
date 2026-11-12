package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.RoomTransport

/**
 * The serverless room topology, expressed as a small testable hub.
 *
 * ## Topology
 *
 * WebRTC gives us unicast only: there is no "broadcast" primitive, and a full
 * mesh among twelve participants would mean sixty-six peer connections, sixty-six
 * DTLS handshakes and sixty-six QR exchanges. That is unusable.
 *
 * So the host is the hub:
 *
 * ```
 *   guest A  <──> host <──>  guest B
 *                 │
 *               guest C
 * ```
 *
 * Each guest holds exactly one [PeerLink] (to the host). The host holds one per
 * guest. When a guest sends an event, the host delivers it to itself **and**
 * forwards it to every other guest. When the host sends an event, it goes to
 * every guest directly.
 *
 * This preserves the existing engine semantics exactly — the host was already
 * the playback authority, presence coordinator and countdown owner — so no room
 * logic had to change. It also means a guest that leaves can never strand
 * another guest: the only link that matters is its own.
 *
 * ## Why this class has no WebRTC in it
 *
 * Fan-out is where the interesting failure modes live: a guest that never reads,
 * a guest that vanishes mid-broadcast, two guests with the same id, a frame
 * arriving from someone who was already removed. All of that is ordinary logic,
 * and all of it is tested here rather than debugged on a phone.
 */
class PeerHub(
    /** True on the device that created the room. */
    private val isHost: Boolean,
    /** This device's participant id, used to route host frames. */
    private val selfId: String,
    /** Delivers a frame that originated on this device to the room engine. */
    private val deliverLocal: (text: String) -> Unit = {},
    /** Reports a participant that disappeared, so the engine can drop them. */
    private val onPeerLost: (peerId: String) -> Unit = {},
) {

    private val links = LinkedHashMap<String, PeerLink>()

    /**
     * Consecutive failed writes per peer.
     *
     * A `DataChannel.send` returns false while its native buffer is briefly
     * full — the guest is still connected and still reading, just a moment
     * behind. Dropping them for that would make playback ticks (which are
     * periodic and idempotent) kick participants out of the room at random. So a
     * link is only dropped after [MAX_CONSECUTIVE_FAILURES] consecutive
     * refusals; one missed heartbeat is not evidence of a dead peer.
     */
    private val consecutiveFailures = HashMap<String, Int>()

    /** Ids currently connected, in join order. */
    val peerIds: List<String> get() = links.keys.toList()

    val size: Int get() = links.size

    /**
     * Registers a connection.
     *
     * A second connection claiming an id that is already live replaces the old
     * one: that is a reconnect (same person, new QR), and keeping both would
     * double every frame they send.
     */
    fun attach(link: PeerLink) {
        links.remove(link.peerId)?.let { previous ->
            runCatching { previous.close() }
        }
        links[link.peerId] = link
        consecutiveFailures.remove(link.peerId)
    }

    /** Removes a connection without notifying anyone (e.g. on teardown). */
    fun detach(peerId: String) {
        links.remove(peerId)
        consecutiveFailures.remove(peerId)
    }

    /**
     * Called when a link breaks unexpectedly.
     *
     * Removes it and tells the engine, exactly once. A duplicate disconnect
     * callback from the underlying stack is harmless because the second call
     * finds nothing to remove.
     */
    fun onLinkClosed(peerId: String) {
        consecutiveFailures.remove(peerId)
        if (links.remove(peerId) != null) {
            onPeerLost(peerId)
        }
    }

    /**
     * Sends [text] originating on this device.
     *
     * On a guest, the frame goes to its single host link. On the host, it goes to
     * every guest.
     */
    fun broadcastFromSelf(text: String) {
        if (!isHost) {
            // A guest has exactly one link — the host.
            links.values.firstOrNull()?.send(text)
            return
        }
        sendToAll(text, except = null)
    }

    /**
     * Handles [text] that arrived from [fromPeerId].
     *
     * On a guest the only possible sender is the host, so the frame goes straight
     * to the engine. On the host the frame is delivered locally **and** fanned
     * out to every *other* guest, which is what makes a three-way conversation
     * work with only unicast links.
     */
    fun onFrameFrom(fromPeerId: String, text: String) {
        if (links[fromPeerId] == null) return // already gone; nothing to route
        deliverLocal(text)
        if (isHost) {
            sendToAll(text, except = fromPeerId)
        }
    }

    /**
     * Fans [text] out, skipping [except].
     *
     * Iterates over a snapshot because a failed write may synchronously close a
     * link, which mutates [links] mid-iteration.
     */
    private fun sendToAll(text: String, except: String?) {
        val snapshot = links.values.toList()
        snapshot.forEach { link ->
            if (link.peerId == except) return@forEach
            if (!link.isOpen) {
                // Closed is unambiguous: report it immediately.
                onLinkClosed(link.peerId)
                return@forEach
            }
            val sent = runCatching { link.send(text) }.getOrDefault(false)
            if (sent) {
                consecutiveFailures.remove(link.peerId)
                return@forEach
            }
            val failures = (consecutiveFailures[link.peerId] ?: 0) + 1
            consecutiveFailures[link.peerId] = failures
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                // Sustained refusal: the peer is not merely a tick behind.
                onLinkClosed(link.peerId)
            }
        }
    }

    /** Closes every link. Used when the room is left or the screen is destroyed. */
    fun closeAll() {
        val snapshot = links.values.toList()
        links.clear()
        snapshot.forEach { runCatching { it.close() } }
    }

    /**
     * Adapts the hub to the existing [RoomTransport] contract.
     *
     * The whole room layer — engine, router, ready check, sync, chat — was
     * written against `RoomTransport`. Rather than duplicate it, the hub presents
     * the same shape: `connect` is a no-op because connections are added one at a
     * time as guests pair, and `send` fans out.
     */
    fun asTransport(): RoomTransport = object : RoomTransport {
        override var listener: RoomTransport.Listener? = null

        override fun connect() {
            // Nothing to dial: each guest is connected by a QR exchange before
            // this transport is ever used.
            listener?.onStateChanged(RoomTransport.State.Connected)
        }

        override fun send(text: String) = broadcastFromSelf(text)

        override fun close() = closeAll()
    }

    companion object {
        /** Practical ceiling before a hub stops being a "small" room. */
        const val MAX_GUESTS = 11

        /**
         * Failed writes tolerated before a guest is considered gone.
         *
         * At the engine's broadcast rate this is a couple of seconds of
         * backpressure — long enough to ride out a buffer stall, short enough
         * that a genuinely dead peer is noticed promptly.
         */
        const val MAX_CONSECUTIVE_FAILURES = 3

        fun canAcceptAnotherGuest(currentGuests: Int): Boolean = currentGuests < MAX_GUESTS
    }
}
