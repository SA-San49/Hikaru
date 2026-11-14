package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Guest side of serverless pairing: scans an invitation, produces the answer
 * code the host scans back, and owns the single resulting connection.
 *
 * A guest has exactly one connection for its whole session. That asymmetry with
 * the host is deliberate and is what keeps the topology simple — see [PeerHub].
 */
class P2PGuestCoordinator(
    private val factoryHolder: PeerConnectionFactoryHolder,
    private val roomId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Everything the guest UI needs to render. */
    data class GuestState(
        val state: PairingState = PairingState.IDLE,
        /** The answer code to show the host, once it exists. */
        val answerPayload: PairingPayload.Payload? = null,
        /** Chunked rendering of that code, when it does not fit on one QR. */
        val answerChunks: List<String> = emptyList(),
        /** The host's verification code, so the guest can compare screens. */
        val hostVerificationCode: String? = null,
        val localVerificationCode: String? = null,
        val failureReason: String? = null,
        val connected: Boolean = false,
        /** Countdown of the host's invitation, which may still matter on screen. */
        val invitationSecondsRemaining: Long = 0L,
    )

    var listener: Listener? = null

    interface Listener {
        /** The answer code is ready; show it for the host to scan. */
        fun onAnswerReady(payload: PairingPayload.Payload)

        /** The channel is open; the room can start. */
        fun onConnected(link: PeerLink)

        /** Pairing failed, with a reason that names the likely cause. */
        fun onFailed(reason: String)
    }

    private val _state = MutableStateFlow(GuestState())
    val state: StateFlow<GuestState> = _state.asStateFlow()

    private val lock = Any()
    private var session: P2PSession? = null

    /** Where inbound frames go; set by the room layer. */
    var messageSink: ((text: String) -> Unit)? = null

    /**
     * Begins pairing from a scanned or pasted invitation.
     *
     * Returns `false` when the code is unusable, and leaves [state] holding a
     * reason. The guest never creates a `PeerConnection` for a code that is
     * already dead: that work is wasted and the failure would be slower and
     * vaguer than saying "that code expired" immediately.
     */
    fun beginPairing(payload: PairingPayload.Payload): Boolean {
        val previousToClose: P2PSession?
        synchronized(lock) {
            val existing = session
            if (existing != null) {
                if (existing.isConnected || _state.value.state == PairingState.CONNECTED) {
                    logcat(LogPriority.WARN) { "WatchParty P2P: already connected to host" }
                    return false
                }
                previousToClose = existing
                session = null
            } else {
                previousToClose = null
            }
        }
        previousToClose?.close()

        val pairing = PairingSession(
            role = PairingPayload.Role.ANSWER,
            roomId = payload.roomId.ifBlank { roomId },
            clock = clock,
        )
        when (val result = pairing.acceptInvitation(payload, clock())) {
            is PairingSession.Result.Rejected -> {
                _state.value = GuestState(
                    state = pairing.state,
                    failureReason = result.reason,
                )
                listener?.onFailed(result.reason)
                return false
            }
            is PairingSession.Result.Accepted -> Unit
        }

        val created = P2PSession(pairing, factoryHolder, events())
        synchronized(lock) {
            session = created
        }
        _state.value = GuestState(
            state = pairing.state,
            hostVerificationCode = payload.verificationCode,
            invitationSecondsRemaining = secondsUntil(payload.expiresAtMs),
        )
        // The invitation that was just accepted; `acceptInvitation` stored it.
        return try {
            created.createAnswerFor(pairing.remotePayload ?: payload)
            true
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "WatchParty P2P: failed to create guest answer" }
            synchronized(lock) {
                if (session === created) session = null
            }
            runCatching { created.close() }
            val reason = when {
                e is UnsatisfiedLinkError || e.message.orEmpty().contains("jingle", ignoreCase = true) ->
                    "This build cannot load the WebRTC engine, so direct pairing is unavailable."
                !e.message.isNullOrBlank() ->
                    "Could not read the invitation: ${e.message}"
                else ->
                    "Could not read the invitation. Ask the host for a new one."
            }
            _state.value = _state.value.copy(
                state = PairingState.FAILED,
                answerPayload = null,
                answerChunks = emptyList(),
                connected = false,
                failureReason = reason,
            )
            listener?.onFailed(reason)
            false
        }
    }

    private fun events() = object : P2PSession.Event {
        override fun onLocalDescription(session: P2PSession, parsed: SdpTemplate.Parsed) {
            if (!isCurrentSession(session)) return
            val pairing = session.pairing
            val answer = PairingPayload.withFreshExpiry(
                role = PairingPayload.Role.ANSWER,
                sessionId = pairing.sessionId,
                roomId = pairing.roomId,
                ufrag = parsed.ufrag,
                pwd = parsed.pwd,
                fingerprint = parsed.fingerprint,
                candidates = parsed.candidates,
                nowMs = clock(),
            )
            pairing.onAnswerPublished(answer)
            val chunks = ChunkedPayload.encodeForDisplay(
                payload = PairingPayload.encode(answer),
                sessionId = PairingSession.shortId(answer.sessionId),
            )

            _state.value = _state.value.copy(
                state = pairing.state,
                answerPayload = answer,
                answerChunks = chunks,
                localVerificationCode = answer.verificationCode,
                failureReason = null,
                invitationSecondsRemaining = secondsUntil(answer.expiresAtMs),
            )
            listener?.onAnswerReady(answer)
        }

        override fun onLocalCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate) {
            // The answer QR is a snapshot. Candidates found later cannot be
            // delivered until the channel exists, which is exactly the case a
            // direct connection needs them for — so they are logged rather than
            // silently discarded, and ICE continues with what the answer carried.
            logcat(LogPriority.VERBOSE) { "WatchParty P2P: late guest candidate" }
        }

        override fun onConnected(session: P2PSession) {
            if (!isCurrentSession(session)) return
            _state.value = _state.value.copy(
                state = PairingState.CONNECTED,
                connected = true,
                failureReason = null,
            )
            val link = P2PSessionLink(session, peerId = "host") { session.dataChannel() }
            listener?.onConnected(link)
        }

        override fun onDisconnected(session: P2PSession, reason: String) {
            val isCurrent = synchronized(lock) {
                if (this@P2PGuestCoordinator.session === session) {
                    this@P2PGuestCoordinator.session = null
                    true
                } else {
                    false
                }
            }
            if (!isCurrent) return
            runCatching { session.close() }
            _state.value = _state.value.copy(
                state = PairingState.FAILED,
                connected = false,
                failureReason = reason,
            )
            listener?.onFailed(reason)
        }

        override fun onMessage(session: P2PSession, text: String) {
            if (!isCurrentSession(session)) return
            messageSink?.invoke(text)
        }
    }

    private fun isCurrentSession(target: P2PSession): Boolean =
        synchronized(lock) { session === target }

    /**
     * Applies the host's trickled candidates.
     *
     * The host cannot send these over the data channel before it exists, so in
     * the serverless flow they usually never arrive. They are accepted anyway so
     * a future transport that can carry them early needs no changes here.
     */
    fun addRemoteCandidates(candidates: List<PairingPayload.IceCandidate>) {
        val current = synchronized(lock) { session } ?: return
        candidates.forEach(current::addRemoteCandidate)
    }

    /** Recomputes the invitation countdown; called from the room ticker. */
    fun tick() {
        val payloadState = _state.value.answerPayload ?: return
        if (_state.value.connected) return
        _state.value = _state.value.copy(
            invitationSecondsRemaining = secondsUntil(payloadState.expiresAtMs),
        )
    }

    /** Tears down the pairing attempt. */
    fun cancel() {
        val existing = synchronized(lock) {
            session.also { session = null }
        }
        existing?.close()
        _state.value = GuestState()
    }

    private fun secondsUntil(expiresAtMs: Long): Long {
        val remaining = expiresAtMs - clock()
        return if (remaining <= 0L) 0L else (remaining + 999L) / 1_000L
    }
}
