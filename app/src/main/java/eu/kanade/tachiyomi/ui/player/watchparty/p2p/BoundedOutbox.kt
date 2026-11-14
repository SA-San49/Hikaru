package eu.kanade.tachiyomi.ui.player.watchparty.p2p

/**
 * Bounded, drop-oldest outbound queue for one peer.
 *
 * A WebRTC DataChannel has its own buffer, but `DataChannel.send()` does not
 * block and does not tell you when the peer stopped reading — it silently grows
 * an internal buffer until the connection is considered backed up, at which
 * point sends throw. Neither failure mode is acceptable here: a guest on a bad
 * connection must never stall the host, and must never take the room down.
 *
 * So every frame goes through one of these first:
 *
 *  - the queue is capped at [capacity] frames;
 *  - when full, the **oldest** frame is discarded to make room;
 *  - every discard is counted, so the caller can tell the user that they are
 *    missing updates instead of pretending the stream is healthy.
 *
 * Dropping the oldest frame is the correct choice for this protocol: newer
 * playback positions, chat lines and presence updates supersede older ones. A
 * gap in the middle of the stream is survivable; falling minutes behind is not.
 *
 * This class is deliberately free of Android and WebRTC types so the drop and
 * isolation policy can be tested without a device or a network.
 */
class BoundedOutbox(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val queue = ArrayDeque<String>()

    /** Total frames discarded because the queue was full. */
    var droppedFrames: Long = 0L
        private set

    /** Frames accepted but not yet taken by the sender. */
    val size: Int get() = queue.size

    val isEmpty: Boolean get() = queue.isEmpty()

    /**
     * True when this peer has fallen far enough behind that continuing to queue
     * for it is dishonest — the UI shows them as stalled rather than "connected".
     *
     * A single burst drop is normal on a slow network and recovers on its own;
     * only sustained loss marks the peer as stalled.
     */
    val stalled: Boolean get() = droppedFrames >= STALL_THRESHOLD

    /** Result of an attempt to enqueue. */
    sealed interface Result {
        /** Frame queued, nothing discarded. */
        data object Queued : Result

        /** Frame queued after discarding [count] oldest frames. */
        data class QueuedAfterDropping(val count: Int) : Result

        /** Frame is larger than the entire queue; it was discarded. */
        data object Rejected : Result
    }

    /**
     * Enqueues [frame], discarding the oldest entry when the queue is full.
     *
     * A single frame larger than the whole capacity can never make progress, so
     * it is rejected outright instead of flushing the queue for nothing.
     */
    fun offer(frame: String): Result {
        if (frame.length > MAX_FRAME_CHARS) return Result.Rejected

        var discarded = 0
        while (queue.size >= capacity) {
            queue.removeFirst()
            discarded++
            droppedFrames++
        }
        queue.addLast(frame)
        return if (discarded == 0) Result.Queued else Result.QueuedAfterDropping(discarded)
    }

    /** Next frame to write without removing it, or `null` when empty. */
    fun peek(): String? = queue.firstOrNull()

    /** Next frame to write, or `null` when drained. */
    fun poll(): String? = queue.removeFirstOrNull()

    /** Discards everything belonging to a peer that has gone away. */
    fun clear() = queue.clear()

    /** Forget accumulated drop counts, e.g. after the peer recovers. */
    fun resetStats() {
        droppedFrames = 0
    }

    companion object {
        /**
         * Roughly 30 seconds of traffic at the engine's presence/position rate.
         * Long enough to ride out a Wi-Fi hiccup, short enough that a lost peer
         * is noticed quickly.
         */
        const val DEFAULT_CAPACITY = 96

        /** No legitimate room frame is anywhere near this; guards against abuse. */
        const val MAX_FRAME_CHARS = 64 * 1024

        /** Two dozen lost frames means the peer is not merely jittery. */
        const val STALL_THRESHOLD = 24L
    }
}
