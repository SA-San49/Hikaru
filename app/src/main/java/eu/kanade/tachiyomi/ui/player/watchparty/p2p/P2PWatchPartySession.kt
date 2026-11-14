package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.content.Context
import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import eu.kanade.tachiyomi.ui.player.watchparty.RoomTransport
import eu.kanade.tachiyomi.ui.player.watchparty.SignallingMode
import eu.kanade.tachiyomi.ui.player.watchparty.WatchPartyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
                _pairing.update { it.copy(connectedGuests = hub?.size ?: 0) }
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
    @Volatile
    private var guestLink: P2PSessionLink? = null

    @Volatile
    private var activeGuestTransport: GuestTransport? = null

    /** Frames arriving from peers, already routed by the hub. */
    var onLocalFrame: ((text: String) -> Unit)? = null

    /** Called when a guest joins or leaves, so the engine can refresh presence. */
    var onParticipantsChanged: (() -> Unit)? = null

    init {
        host?.listener = object : P2PHostCoordinator.Listener {
            override fun onInvitationReady(session: P2PSession, payload: PairingPayload.Payload) {
                val codes = ChunkedPayload.encodeForDisplay(
                    payload = PairingPayload.encode(payload),
                    sessionId = PairingSession.shortId(payload.sessionId),
                )
                _pairing.update { current ->
                    current.copy(
                        state = session.pairing.state,
                        codes = codes,
                        expiresAtMs = payload.expiresAtMs,
                        secondsRemaining = session.pairing.secondsRemaining(clock()),
                        verificationCode = payload.verificationCode,
                        failureReason = null,
                        connectedGuests = hub?.size ?: current.connectedGuests,
                    )
                }
            }

            override fun onInvitationCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate) {
                // The invitation QR is a snapshot; later candidates would need a
                // second exchange. Logged rather than silently dropped.
                logcat(LogPriority.VERBOSE) { "WatchParty P2P: late host candidate" }
            }

            override fun onGuestConnected(session: P2PSession, link: PeerLink) {
                hub?.attach(link)
                val connectedCount = hub?.size ?: 0
                _pairing.update {
                    it.copy(
                        state = PairingState.CONNECTED,
                        codes = emptyList(),
                        expiresAtMs = 0L,
                        secondsRemaining = 0L,
                        failureReason = null,
                        connectedGuests = connectedCount,
                    )
                }
                onParticipantsChanged?.invoke()
            }

            override fun onGuestLost(session: P2PSession, reason: String) {
                val wasInHub = hub?.size ?: 0
                hub?.onLinkClosed(session.pairing.sessionId)
                val nowInHub = hub?.size ?: 0
                if (wasInHub > nowInHub) {
                    onParticipantsChanged?.invoke()
                }
                if (!session.isConnected) {
                    _pairing.update {
                        it.copy(
                            state = if (nowInHub > 0) PairingState.CONNECTED else PairingState.FAILED,
                            codes = emptyList(),
                            expiresAtMs = 0L,
                            secondsRemaining = 0L,
                            failureReason = reason,
                            connectedGuests = nowInHub,
                        )
                    }
                } else if (wasInHub != nowInHub) {
                    _pairing.update { it.copy(connectedGuests = nowInHub) }
                }
            }

            override fun onInvitationsChanged(invitations: List<P2PHostCoordinator.Invitation>) {
                // Prefer the most recent invitation that is still in-flight (so
                // inviting a second guest shows the second guest's QR rather than
                // staying stuck on guest #1's CONNECTED state).
                val current = invitations.lastOrNull { it.state != PairingState.CONNECTED }
                    ?: invitations.lastOrNull()
                val connectedCount = hub?.size ?: invitations.count { it.state == PairingState.CONNECTED }
                val failure = current?.failureReason ?: host?.failure?.value
                val codes = if (current?.state == PairingState.WAITING_FOR_GUEST && current.payload != null) {
                    ChunkedPayload.encodeForDisplay(
                        payload = PairingPayload.encode(current.payload),
                        sessionId = PairingSession.shortId(current.payload.sessionId),
                    )
                } else {
                    emptyList()
                }
                val resolvedState = when {
                    current != null -> current.state
                    failure != null -> PairingState.FAILED
                    connectedCount > 0 -> PairingState.CONNECTED
                    else -> PairingState.IDLE
                }
                _pairing.update {
                    it.copy(
                        state = resolvedState,
                        codes = codes,
                        expiresAtMs = current?.expiresAtMs ?: 0L,
                        secondsRemaining = current?.secondsRemaining ?: 0L,
                        verificationCode = current?.verificationCode,
                        failureReason = failure,
                        connectedGuests = connectedCount,
                    )
                }
            }
        }
        host?.messageSink = { peerId, text -> hub?.onFrameFrom(peerId, text) }

        guest?.listener = object : P2PGuestCoordinator.Listener {
            override fun onAnswerReady(payload: PairingPayload.Payload) {
                val snapshot = guest.state.value
                _pairing.update {
                    it.copy(
                        state = snapshot.state,
                        codes = snapshot.answerChunks,
                        expiresAtMs = payload.expiresAtMs,
                        secondsRemaining = snapshot.invitationSecondsRemaining,
                        verificationCode = snapshot.localVerificationCode,
                        remoteVerificationCode = snapshot.hostVerificationCode,
                        failureReason = null,
                    )
                }
            }

            override fun onConnected(link: PeerLink) {
                guestLink = link as? P2PSessionLink
                _pairing.update {
                    it.copy(
                        state = PairingState.CONNECTED,
                        codes = emptyList(),
                        expiresAtMs = 0L,
                        secondsRemaining = 0L,
                        failureReason = null,
                        connectedGuests = 1,
                    )
                }
                activeGuestTransport?.notifyConnected()
                onParticipantsChanged?.invoke()
            }

            override fun onFailed(reason: String) {
                guestLink = null
                _pairing.update {
                    it.copy(
                        state = PairingState.FAILED,
                        codes = emptyList(),
                        expiresAtMs = 0L,
                        secondsRemaining = 0L,
                        failureReason = reason,
                        connectedGuests = 0,
                    )
                }
                activeGuestTransport?.notifyFailed(reason)
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
    /** Pre-warms native WebRTC initialization off the UI path before pairing starts. */
    fun warmup() {
        factoryHolder.factory()
    }

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
        val payload = ChunkedPayload.decodePayload(raw) ?: run {
            _pairing.value = _pairing.value.copy(
                failureReason = "That is not a Hikaru Watch Party answer code.",
            )
            return false
        }
        return host?.acceptAnswerForOnlyWaitingInvitation(payload) ?: false
    }

    /** Guest: begins pairing from a scanned or pasted invitation. */
    fun beginPairing(raw: String): Boolean {
        val payload = ChunkedPayload.decodePayload(raw) ?: run {
            _pairing.value = _pairing.value.copy(
                state = PairingState.FAILED,
                failureReason = "That is not a Hikaru Watch Party code.",
            )
            return false
        }
        return beginPairing(payload)
    }

    /**
     * Guest: begins pairing from an already-decoded invitation.
     *
     * Separate from the string form because the room layer decodes the payload
     * once to learn the room id, and decoding twice would be wasteful and could
     * disagree if the string were re-interpreted.
     */
    fun beginPairing(payload: PairingPayload.Payload): Boolean {
        val ok = guest?.beginPairing(payload) ?: false
        val guestSnapshot = guest?.state?.value
        if (guestSnapshot != null) {
            _pairing.value = _pairing.value.copy(
                state = guestSnapshot.state,
                codes = guestSnapshot.answerChunks,
                verificationCode = guestSnapshot.localVerificationCode,
                remoteVerificationCode = guestSnapshot.hostVerificationCode,
                failureReason = guestSnapshot.failureReason,
                secondsRemaining = guestSnapshot.invitationSecondsRemaining,
            )
        }
        return ok
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
        return GuestTransport().also { activeGuestTransport = it }
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

        fun notifyConnected() {
            listener?.onStateChanged(RoomTransport.State.Connected)
        }

        fun notifyFailed(reason: String) {
            listener?.onStateChanged(RoomTransport.State.Failed(reason))
        }

        override fun send(text: String) {
            val link = guestLink ?: return
            if (!link.send(text)) {
                listener?.onStateChanged(
                    RoomTransport.State.Failed("The connection to the host was lost."),
                )
            }
        }

        override fun close() {
            if (activeGuestTransport === this) {
                activeGuestTransport = null
            }
            listener = null
            guestLink?.close()
            guestLink = null
        }
    }

    /**
     * Folds a host-side failure into the pairing snapshot.
     *
     * Kept as an explicit call rather than a collected flow because this class has
     * no coroutine scope of its own: the room scope owns the lifecycle and pushes
     * failures in, which avoids a second scope that could outlive the room.
     */
    fun reportHostFailure(reason: String?) {
        if (reason == null) return
        val current = _pairing.value
        // Preserve WAITING_FOR_GUEST QR if an invalid answer was rejected on screen;
        // only move to FAILED when there is no active invitation QR.
        val nextState = if (current.state == PairingState.WAITING_FOR_GUEST && current.codes.isNotEmpty()) {
            PairingState.WAITING_FOR_GUEST
        } else {
            PairingState.FAILED
        }
        _pairing.value = current.copy(
            state = nextState,
            failureReason = reason,
        )
    }

    /** Recomputes countdowns. Called from the engine ticker. */
    fun tick() {
        host?.tick()
        guest?.tick()
        _pairing.update { current ->
            if (current.expiresAtMs > 0L) {
                val remaining = (current.expiresAtMs - clock()).coerceAtLeast(0L)
                current.copy(secondsRemaining = (remaining + 999L) / 1_000L)
            } else {
                current
            }
        }
    }

    /** Releases every connection and the shared WebRTC factory. */
    fun close() {
        onLocalFrame = null
        onParticipantsChanged = null
        activeGuestTransport = null
        host?.closeAll()
        guest?.cancel()
        hub?.closeAll()
        guestLink = null
        _pairing.value = PairingSnapshot()
    }

    // </editor-fold>

    companion object {
        /** True when this build should use QR pairing rather than the relay. */
        fun usesServerlessPairing(prefs: WatchPartyPreferences): Boolean =
            prefs.signallingMode().get() == SignallingMode.SERVERLESS_QR
    }
}
