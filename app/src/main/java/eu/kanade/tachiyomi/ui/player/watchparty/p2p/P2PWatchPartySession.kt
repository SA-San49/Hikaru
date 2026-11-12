package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.content.Context
import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import eu.kanade.tachiyomi.ui.player.watchparty.RoomTransport
import eu.kanade.tachiyomi.ui.player.watchparty.SignallingMode
import eu.kanade.tachiyomi.ui.player.watchparty.WatchPartyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns everything serverless for one room: the host's invitations, the guest's
 * pairing attempt, and the hub that carries room traffic.
 *
 * One object per room, created when the user creates or joins it and destroyed
 * when they leave. Nothing here is initialised until that happens, so a player
 * that never opens Watch Party never touches WebRTC, the camera or a socket.
 *
 * ## Who talks to whom
 *
 * The room engine was written against [RoomTransport]. Rather than fork it, this
 * class hands it the hub:
 *
 *  - the **host** attaches every connected guest to a [PeerHub] and returns
 *    `hub.asTransport()`;
 *  - the **guest** returns its single host link as a transport.
 *
 * In both cases the engine sees a normal transport that fans out frames, so
 * presence, playback authority, ready checks, sync, chat and reactions behave
 * exactly as they do over the relay.
 */
class P2PWatchPartySession(
    context: Context,
    private val prefs: WatchPartyPreferences,
    roomId: String,
    val isHost: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Anime-level room identity.
     *
     * Settable because the host does not know it until the engine creates the
     * room, which happens after this session exists. It is bound immediately
     * afterwards and never changes for the life of the room.
     */
    var roomId: String = roomId
        private set

    /** ICE servers come from settings; empty in LAN-only mode. */
    private val factoryHolder = PeerConnectionFactoryHolder(context.applicationContext) {
        if (prefs.lanOnly().get()) emptyList() else prefs.stunServers().get().split(',')
    }

    private val _pairing = MutableStateFlow(PairingSnapshot())
    val pairing: StateFlow<PairingSnapshot> = _pairing.asStateFlow()

    /** What the pairing UI renders, unified across both roles. */
    data class PairingSnapshot(
        val state: PairingState = PairingState.IDLE,
        /** Codes to display. On a host these are invitations; on a guest, answers. */
        val codes: List<String> = emptyList(),
        val expiresAtMs: Long = 0L,
        val secondsRemaining: Long = 0L,
        val verificationCode: String? = null,
        val remoteVerificationCode: String? = null,
        val failureReason: String? = null,
        val connectedGuests: Int = 0,
    )

    val host: P2PHostCoordinator? = if (isHost) {
        P2PHostCoordinator(factoryHolder, clock).also { it.setRoom(roomId) }
    } else {
        null
    }

    val guest: P2PGuestCoordinator? = if (isHost) {
        null
    } else {
        P2PGuestCoordinator(factoryHolder, roomId, clock)
    }

    /** Host-only fan-out hub. */
    private val hub: PeerHub? = if (isHost) {
        PeerHub(
            isHost = true,
            selfId = prefs.participantId(),
            deliverLocal = { text -> onLocalFrame?.invoke(text) },
            onPeerLost = { peerId ->
                host?.removeSession(peerId)
                _pairing.value = _pairing.value.copy(connectedGuests = hub?.size ?: 0)
            },
        )
    } else {
        null
    }

    /**
     * Guest-only link to the host.
     *
     * Held as the concrete type because the guest needs the RoomTransport adapter,
     * which only makes sense for the single connection a guest ever has.
     */
    private var guestLink: P2PSessionLink? = null

    /** Frames arriving from peers, already routed by the hub. */
    var onLocalFrame: ((text: String) -> Unit)? = null

    /** Called when a guest joins or leaves, so the engine can refresh presence. */
    var onParticipantsChanged: (() -> Unit)? = null

    init {
        host?.listener = object : P2PHostCoordinator.Listener {
            override fun onInvitationReady(session: P2PSession, payload: PairingPayload.Payload) {
                publishCodes(ChunkedPayload.split(PairingPayload.encode(payload), PairingSession.shortId(payload.sessionId)))
            }

            override fun onInvitationCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate) {
                // The invitation QR is a snapshot; later candidates would need a
                // second exchange. Logged rather than silently dropped.
                logcat(LogPriority.VERBOSE) { "WatchParty P2P: late host candidate" }
            }

            override fun onGuestConnected(session: P2PSession, link: PeerLink) {
                hub?.attach(link)
                _pairing.value = _pairing.value.copy(
                    state = PairingState.CONNECTED,
                    connectedGuests = hub?.size ?: 0,
                )
                onParticipantsChanged?.invoke()
            }

            override fun onGuestLost(session: P2PSession, reason: String) {
                hub?.onLinkClosed(session.pairing.sessionId)
                _pairing.value = _pairing.value.copy(connectedGuests = hub?.size ?: 0)
                onParticipantsChanged?.invoke()
            }

            override fun onInvitationsChanged(invitations: List<P2PHostCoordinator.Invitation>) {
                val first = invitations.firstOrNull()
                if (first != null) {
                    _pairing.value = _pairing.value.copy(
                        state = first.state,
                        expiresAtMs = first.expiresAtMs,
                        secondsRemaining = first.secondsRemaining,
                        verificationCode = first.verificationCode,
                        failureReason = first.failureReason,
                        connectedGuests = hub?.size ?: 0,
                    )
                }
            }
        }
        host?.messageSink = { peerId, text -> hub?.onFrameFrom(peerId, text) }

        guest?.listener = object : P2PGuestCoordinator.Listener {
            override fun onAnswerReady(payload: PairingPayload.Payload) {
                val snapshot = guest.state.value
                _pairing.value = _pairing.value.copy(
                    state = snapshot.state,
                    codes = snapshot.answerChunks,
                    expiresAtMs = payload.expiresAtMs,
                    secondsRemaining = snapshot.invitationSecondsRemaining,
                    verificationCode = snapshot.localVerificationCode,
                    remoteVerificationCode = snapshot.hostVerificationCode,
                )
            }

            override fun onConnected(link: PeerLink) {
                guestLink = link as? P2PSessionLink
                _pairing.value = _pairing.value.copy(
                    state = PairingState.CONNECTED,
                    connectedGuests = 1,
                )
                onParticipantsChanged?.invoke()
            }

            override fun onFailed(reason: String) {
                _pairing.value = _pairing.value.copy(
                    state = PairingState.FAILED,
                    failureReason = reason,
                )
            }
        }
        guest?.messageSink = { text -> onLocalFrame?.invoke(text) }
    }

    /**
     * Binds the room id the engine settled on.
     *
     * Host only: a guest knows the room id from the code it typed. Called once,
     * immediately after the engine creates the room, and never again.
     */
    fun bindRoom(roomId: String) {
        if (this.roomId == roomId) return
        this.roomId = roomId
        host?.setRoom(roomId)
    }

    // <editor-fold desc="Host / guest entry points">

    /** Host: creates another invitation and shows its code. */
    fun createInvitation(): Boolean {
        val created = host?.createInvitation() ?: return false
        return created.isNotBlank()
    }

    /** Host: applies an answer the guest scanned back. */
    fun acceptAnswer(raw: String): Boolean {
        val payload = PairingPayload.decode(raw) ?: return false
        return host?.acceptAnswerForOnlyWaitingInvitation(payload) ?: false
    }

    /** Guest: begins pairing from a scanned or pasted invitation. */
    fun beginPairing(raw: String): Boolean {
        val payload = PairingPayload.decode(raw) ?: run {
            _pairing.value = _pairing.value.copy(
                state = PairingState.FAILED,
                failureReason = "That is not a Hikaru Watch Party code.",
            )
            return false
        }
        return guest?.beginPairing(payload) ?: false
    }

    // </editor-fold>

    // <editor-fold desc="Transport">

    /**
     * The transport the room engine should use.
     *
     * Returned up front even before any guest has paired, because the engine
     * announces presence immediately; frames sent before a guest exists simply
     * have nowhere to go, which is correct.
     */
    fun transport(): RoomTransport {
        hub?.let { return it.asTransport() }
        // A guest asks for its transport *before* pairing has happened, so
        // returning whatever link exists at that moment would hand the engine a
        // dead object for the rest of the room's life. This delegates every call
        // to the current link instead, so it starts working the moment the
        // channel opens.
        return GuestTransport()
    }

    /**
     * A guest's single transport, resolved lazily against [guestLink].
     *
     * Identity is stable for the room's lifetime; only the target changes, once,
     * when the host's data channel opens.
     */
    private inner class GuestTransport : RoomTransport {
        override var listener: RoomTransport.Listener? = null

        override fun connect() {
            listener?.onStateChanged(
                if (guestLink?.isOpen == true) RoomTransport.State.Connected else RoomTransport.State.Connecting,
            )
        }

        override fun send(text: String) {
            val link = guestLink
            if (link == null) {
                // Nothing to send to yet: pairing has not finished. Reporting a
                // failure here would be wrong, and dropping silently would hide a
                // genuine regression, so it is logged once per occurrence.
                return
            }
            if (!link.send(text)) {
                listener?.onStateChanged(
                    RoomTransport.State.Failed("The connection to the host was lost."),
                )
            }
        }

        override fun close() {
            guestLink?.close()
            guestLink = null
        }
    }

    /** Recomputes countdowns. Called from the engine ticker. */
    fun tick() {
        host?.tick()
        guest?.tick()
        val current = _pairing.value
        if (current.expiresAtMs > 0L) {
            val remaining = (current.expiresAtMs - clock()).coerceAtLeast(0L)
            _pairing.value = current.copy(secondsRemaining = (remaining + 999L) / 1_000L)
        }
    }

    /** Releases every connection and the shared WebRTC factory. */
    fun close() {
        onLocalFrame = null
        onParticipantsChanged = null
        host?.closeAll()
        guest?.cancel()
        hub?.closeAll()
        guestLink = null
        _pairing.value = PairingSnapshot()
    }

    private fun publishCodes(chunks: List<ChunkedPayload.Chunk>) {
        _pairing.value = _pairing.value.copy(codes = chunks.map { it.render() })
    }

    // </editor-fold>

    companion object {
        /** True when this build should use QR pairing rather than the relay. */
        fun usesServerlessPairing(prefs: WatchPartyPreferences): Boolean =
            prefs.signallingMode().get() == SignallingMode.SERVERLESS_QR
    }
}
