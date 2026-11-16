package eu.kanade.tachiyomi.ui.player.watchparty.p2p

/**
 * One live connection to one participant, as the room layer sees it.
 *
 * Deliberately free of `org.webrtc` types. [P2PSession] implements it over a
 * `DataChannel`; tests implement it over a `MutableList`. Everything that is
 * worth getting right about fan-out, backpressure and departure handling lives
 * above this interface, where it can be executed.
 */
interface PeerLink {

    /** Stable id of the participant on the other end. */
    val peerId: String

    /** Last known display name; may be blank before their first frame. */
    val peerName: String

    /** True while frames can be written. */
    val isOpen: Boolean

    /**
     * Writes [text].
     *
     * Implementations must not block and must not throw: a link that cannot
     * accept a frame reports failure by returning `false`, so one broken guest
     * cannot interrupt a broadcast that is already in progress.
     */
    fun send(text: String): Boolean

    /** Closes the connection and releases its resources. */
    fun close()
}
