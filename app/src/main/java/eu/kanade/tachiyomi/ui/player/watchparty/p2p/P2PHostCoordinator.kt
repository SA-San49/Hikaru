package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Host side of serverless pairing: creates one invitation per guest and owns
 * every resulting connection.
 *
 * ## One offer per guest, never reused
 *
 * Each call to [createInvitation] builds a fresh [PairingSession], which means a
 * fresh `PeerConnection`, ufrag, DTLS certificate and QR code. When a guest
 * leaves, their session is closed and its code stops working. This is the single
 * most important invariant in the whole feature: a reused offer produces a
 * connection that *looks* established to both sides while only one of them can
 * actually send.
 *
 * ## What the UI sees
 *
 * [invitations] is the list of QR codes currently on screen. Normally one, but a
 * host waiting for several friends can show several — each is an independent
 * handshake and they never interfere.
 */
class P2PHostCoordinator(
    private val factoryHolder: PeerConnectionFactoryHolder,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** One outstanding invitation, from QR code to connected guest. */
    data class Invitation(
        val sessionId: String,
        val state: PairingState,
        val payload: PairingPayload.Payload?,
        val expiresAtMs: Long,
        val secondsRemaining: Long,
        val verificationCode: String?,
        val failureReason: String?,
        val guestName: String?,
    )

    /** Invitations whose listener receives UI-facing updates. */
    var listener: Listener? = null

    interface Listener {
        /** A new QR payload is ready to display. */
        fun onInvitationReady(session: P2PSession, payload: PairingPayload.Payload)

        /** A candidate gathered after the QR was shown. */
        fun onInvitationCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate)

        /** A guest finished pairing; their link is ready to attach to the hub. */
        fun onGuestConnected(session: P2PSession, link: PeerLink)

        /** A guest is gone; remove them from the room. */
        fun onGuestLost(session: P2PSession, reason: String)

        /** Any invitation state changed; refresh the UI. */
        fun onInvitationsChanged(invitations: List<Invitation>)
    }

    private val sessions = LinkedHashMap<String, P2PSession>()
    private val _invitations = MutableStateFlow<List<Invitation>>(emptyList())
    val invitations: StateFlow<List<Invitation>> = _invitations.asStateFlow()

    private var roomId: String = ""

    /** Sets the room identity that invitations are bound to. */
    fun setRoom(roomId: String) {
        this.roomId = roomId
    }

    /**
     * Creates a new invitation.
     *
     * Returns `null` when the room is already at [PeerHub.MAX_GUESTS] — the
     * caller shows that as a message rather than silently generating a code
     * nobody can use.
     */
    fun createInvitation(): String? {
        if (!PeerHub.canAcceptAnotherGuest(sessions.size)) {
            logcat(LogPriority.WARN) { "WatchParty P2P: refusing a guest, room is full" }
            return null
        }
        val pairing = PairingSession(
            role = PairingPayload.Role.OFFER,
            roomId = roomId,
            clock = clock,
        )
        val session = P2PSession(pairing, factoryHolder, sessionEvents(pairing.sessionId))
        sessions[pairing.sessionId] = session
        publish()
        session.createOffer()
        return pairing.sessionId
    }

    /**
     * Applies an answer that a guest scanned back.
     *
     * All validation lives in [PairingSession]; this method's job is to find the
     * right session and translate the verdict. A rejected answer leaves the
     * invitation usable, because a guest mistyping a scan should not force the
     * host to start over.
     */
    fun acceptAnswer(sessionId: String, answer: PairingPayload.Payload): Boolean {
        val session = sessions[sessionId] ?: run {
            logcat(LogPriority.WARN) { "WatchParty P2P: answer for an unknown invitation" }
            return false
        }
        return when (val result = session.pairing.acceptRemote(answer, clock())) {
            is PairingSession.Result.Rejected -> {
                logcat(LogPriority.INFO) { "WatchParty P2P: answer rejected (${result.kind})" }
                publish()
                false
            }
            is PairingSession.Result.Accepted -> {
                session.applyAnswer(result.payload)
                publish()
                true
            }
        }
    }

    /**
     * Applies an answer scanned from a QR that did not record which invitation
     * it belonged to.
     *
     * The answer carries the *guest's* session id, not ours, so the invitation is
     * found by room and by being the only one still waiting. Showing the host a
     * chooser for an ambiguous case would be worse than asking them to retry.
     */
    fun acceptAnswerForOnlyWaitingInvitation(answer: PairingPayload.Payload): Boolean {
        val waiting = sessions.values.filter {
            it.pairing.state == PairingState.WAITING_FOR_GUEST && it.pairing.roomId == answer.roomId
        }
        val target = waiting.singleOrNull() ?: run {
            logcat(LogPriority.INFO) {
                "WatchParty P2P: ${waiting.size} invitations are waiting; cannot route that answer"
            }
            return false
        }
        return acceptAnswer(target.pairing.sessionId, answer)
    }

    /** Drops a guest and their invitation. */
    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)?.close()
        publish()
    }

    /** Ends every invitation and connection. */
    fun closeAll() {
        val all = sessions.values.toList()
        sessions.clear()
        all.forEach { runCatching { it.close() } }
        _invitations.value = emptyList()
    }

    /** The link for a connected session, ready to hand to the hub. */
    fun linkFor(sessionId: String): PeerLink? {
        val session = sessions[sessionId] ?: return null
        if (!session.isConnected) return null
        return P2PSessionLink(session, peerId = sessionId) { session.dataChannel() }
    }

    private fun sessionEvents(sessionId: String) = object : P2PSession.Event {
        override fun onLocalDescription(session: P2PSession, parsed: SdpTemplate.Parsed) {
            val payload = PairingPayload.withFreshExpiry(
                role = PairingPayload.Role.OFFER,
                sessionId = session.pairing.sessionId,
                roomId = roomId,
                ufrag = parsed.ufrag,
                pwd = parsed.pwd,
                fingerprint = parsed.fingerprint,
                candidates = parsed.candidates,
                nowMs = clock(),
            )
            session.pairing.onLocalPayload(payload)
            listener?.onInvitationReady(session, payload)
            publish()
        }

        override fun onLocalCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate) {
            listener?.onInvitationCandidate(session, candidate)
        }

        override fun onConnected(session: P2PSession) {
            publish()
            val link = linkFor(sessionId) ?: return
            listener?.onGuestConnected(session, link)
        }

        override fun onDisconnected(session: P2PSession, reason: String) {
            logcat(LogPriority.INFO) { "WatchParty P2P: guest session ended — $reason" }
            sessions.remove(sessionId)
            publish()
            listener?.onGuestLost(session, reason)
        }

        override fun onMessage(session: P2PSession, text: String) {
            // Routed by the coordinator's owner (the engine), never by the UI.
            messageSink?.invoke(sessionId, text)
        }
    }

    /** Where inbound frames go; set by the room layer. */
    var messageSink: ((peerId: String, text: String) -> Unit)? = null

    private fun publish() {
        val now = clock()
        val list = sessions.values.map { session ->
            val pairing = session.pairing
            Invitation(
                sessionId = pairing.sessionId,
                state = pairing.state,
                payload = pairing.payload,
                expiresAtMs = pairing.expiresAtMs,
                secondsRemaining = pairing.secondsRemaining(now),
                verificationCode = pairing.verificationCode(),
                failureReason = pairing.failureReason,
                guestName = pairing.remoteName,
            )
        }
        _invitations.value = list
        listener?.onInvitationsChanged(list)
    }

    /** Recomputes countdowns; called from the engine's ticker. */
    fun tick() {
        if (sessions.isEmpty()) return
        val expired = sessions.values.filter { it.pairing.isExpired(clock()) }
        if (expired.isNotEmpty()) {
            // An expired invitation is dead: close it so the QR on screen stops
            // being scannable, and tell the host.
            expired.forEach { session ->
                session.close()
                sessions.remove(session.pairing.sessionId)
            }
        }
        publish()
    }
}
