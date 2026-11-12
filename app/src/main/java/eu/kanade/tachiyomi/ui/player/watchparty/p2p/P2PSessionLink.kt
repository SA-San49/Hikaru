package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.RoomTransport
import logcat.LogPriority
import org.webrtc.DataChannel
import tachiyomi.core.common.util.system.logcat

/**
 * Bridges a [P2PSession] to the room layer as a [PeerLink].
 *
 * ## Backpressure
 *
 * `DataChannel.send` returns `false` when its native buffer is full. Rather than
 * unregistering the observer or growing a queue inside the SDK, refused frames go
 * into a [BoundedOutbox]. That caps memory per guest and, critically, *counts*
 * what it had to discard so the UI can say "this guest is falling behind"
 * instead of showing a healthy connection that has silently stopped updating.
 *
 * Frames are flushed on the engine's own cadence, which is already adaptive
 * (positions are sent at most a few times a second), so a flush per send is
 * enough — there is no separate writer thread to get out of sync.
 */
class P2PSessionLink(
    private val session: P2PSession,
    override val peerId: String,
    private val dataChannelProvider: () -> DataChannel?,
) : PeerLink {

    private val outbox = BoundedOutbox()

    override var peerName: String = ""

    /** Frames the guest never received because its buffer stayed full. */
    val droppedFrames: Long get() = outbox.droppedFrames

    /** True when this guest has missed enough that the UI should say so. */
    val isStalled: Boolean get() = outbox.stalled

    override val isOpen: Boolean
        get() = dataChannelProvider()?.state() == DataChannel.State.OPEN

    override fun send(text: String): Boolean {
        val channel = dataChannelProvider()
        if (channel == null || channel.state() != DataChannel.State.OPEN) return false

        // Try the wire first: if the buffer has room this is a single write and
        // the queue never grows.
        if (outbox.isEmpty && write(channel, text)) return true

        when (val queued = outbox.offer(text)) {
            is BoundedOutbox.Result.Rejected -> {
                logcat(LogPriority.WARN) { "WatchParty P2P: refusing an oversized frame" }
                return false
            }
            is BoundedOutbox.Result.QueuedAfterDropping -> Unit
            BoundedOutbox.Result.Queued -> Unit
        }
        flush(channel)
        return true
    }

    /** Writes as much of the backlog as the channel will take. */
    private fun flush(channel: DataChannel) {
        while (true) {
            val frame = outbox.poll() ?: return
            if (!write(channel, frame)) {
                // Put it back at the front of the backlog so ordering holds.
                outbox.offer(frame)
                return
            }
        }
    }

    private fun write(channel: DataChannel, text: String): Boolean = runCatching {
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(text.toByteArray()), false))
    }.getOrDefault(false)

    override fun close() {
        outbox.clear()
        session.close()
    }

    /**
     * Adapts a single link to the [RoomTransport] contract for the *guest* side,
     * where there is exactly one connection to the host.
     *
     * The host uses [PeerHub.asTransport] instead, because it has many links.
     */
    fun asGuestTransport(): RoomTransport = object : RoomTransport {
        override var listener: RoomTransport.Listener? = null

        override fun connect() {
            // The data channel was established during pairing, so there is
            // nothing left to dial. Report state from what it actually is.
            val open = isOpen
            listener?.onStateChanged(
                if (open) RoomTransport.State.Connected else RoomTransport.State.Connecting,
            )
        }

        override fun send(text: String) {
            // Qualified deliberately: an unqualified `send` here is this object's
            // own method, which returns Unit and would recurse.
            if (!this@P2PSessionLink.send(text)) {
                listener?.onStateChanged(
                    RoomTransport.State.Failed("The connection to the host was lost."),
                )
            }
        }

        override fun close() = this@P2PSessionLink.close()
    }
}
