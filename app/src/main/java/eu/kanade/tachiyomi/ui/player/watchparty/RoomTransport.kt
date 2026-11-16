package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Abstraction over the room's real-time transport.
 *
 * Two implementations are intended:
 *
 *  - [RelayRoomTransport] (implemented): an OkHttp WebSocket to a tiny relay.
 *    Works across arbitrary networks, NATs and mobile data because it is a plain
 *    outbound connection; the relay only fans out small JSON events.
 *  - a WebRTC DataChannel transport (not implemented yet, see
 *    `docs/watch-party.md`): P2P-first with a relay/TURN fallback. The interface
 *    below is deliberately transport-agnostic so it can be dropped in later
 *    without touching room logic.
 */
interface RoomTransport {

    /** Connection lifecycle as reported by the transport. */
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Connected : State
        data class Failed(val reason: String) : State
    }

    interface Listener {
        fun onStateChanged(state: State)

        /** Called for every raw text frame; decoding happens in the engine. */
        fun onMessage(text: String)
    }

    var listener: Listener?

    fun connect()

    /** Queues [text] for delivery; drops the oldest frame when backed up. */
    fun send(text: String)

    fun close()
}

/** Reasons a room can fail, surfaced to the UI. */
enum class RoomError {
    RELAY_UNREACHABLE,
    BAD_CODE,
    ROOM_FULL,
    KICKED,
    PROTOCOL,
    UNKNOWN,
}
