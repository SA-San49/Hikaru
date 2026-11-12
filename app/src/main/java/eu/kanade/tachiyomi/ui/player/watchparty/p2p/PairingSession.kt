package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import java.util.UUID

/**
 * The pairing state machine for **one** guest connection.
 *
 * Everything in here is plain Kotlin: no WebRTC, no Android, no coroutines
 * beyond a single clock function. That is deliberate — the handshake is where
 * the subtle bugs live (reused offers, scanned-stale-codes, offers that look
 * valid but were already answered), so it must be testable without a device.
 *
 * [P2PSession] owns the `org.webrtc.PeerConnection` and feeds this class:
 * `onOfferCreated`, `onCandidate`, `onConnected`, and asks it `canAcceptAnswer`
 * before applying one.
 *
 * ## Why one session per guest
 *
 * A WebRTC offer is single-use. Reusing one offer for two guests is the classic
 * P2P group-call bug: the second `setRemoteDescription(answer)` either throws or,
 * worse, succeeds and leaves both peers thinking they are connected while only
 * one DTLS association exists. So the host holds a *separate* session, with its
 * own `PeerConnection`, its own ufrag, its own fingerprint and its own QR code,
 * per guest. Nothing is shared but the room.
 */
class PairingSession(
    val role: PairingPayload.Role,
    val roomId: String,
    val sessionId: String = newSessionId(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Volatile state, exposed for the UI and for tests. */
    var state: PairingState = PairingState.IDLE
        private set

    /** Set once our own offer/answer exists and a payload has been produced. */
    var payload: PairingPayload.Payload? = null
        private set

    /** Set once a valid peer payload has been accepted. */
    var remotePayload: PairingPayload.Payload? = null
        private set

    /** Wall-clock expiry of the invitation currently on screen, if any. */
    var expiresAtMs: Long = 0L
        private set

    /** Remote guest's display name, once known. Host side only. */
    var remoteName: String? = null

    /** Reason for [PairingState.FAILED]; never a generic "something went wrong". */
    var failureReason: String? = null
        private set

    /** Candidates produced after the invitation was generated. */
    private val pendingCandidates = mutableListOf<PairingPayload.IceCandidate>()

    val isConnected: Boolean get() = state == PairingState.CONNECTED

    /**
     * Seconds left on the invitation; drives the `MM:SS` label.
     *
     * Rounded *up*, so a code with 500 ms left shows `00:01` rather than
     * `00:00`. A countdown that reads zero while the code still works is a lie
     * in the one direction that matters to the user.
     */
    fun secondsRemaining(nowMs: Long = clock()): Long {
        if (expiresAtMs <= 0L) return 0L
        val remaining = expiresAtMs - nowMs
        return if (remaining <= 0L) 0L else (remaining + 999L) / 1_000L
    }

    /**
     * Whether the invitation on screen has died.
     *
     * Absolute expiry means a guest cannot extend it by scanning slowly, and
     * backgrounding the app does not pause the countdown.
     */
    fun isExpired(nowMs: Long = clock()): Boolean =
        expiresAtMs > 0L && nowMs >= expiresAtMs

    /** Marks the start of local description creation. */
    fun beginLocalDescription() {
        check(state == PairingState.IDLE || state == PairingState.INVITATION_EXPIRED || state == PairingState.FAILED) {
            "cannot restart a session in state $state"
        }
        state = if (role == PairingPayload.Role.OFFER) {
            PairingState.CREATING_INVITATION
        } else {
            PairingState.CREATING_ANSWER
        }
        failureReason = null
        expiresAtMs = 0L
        payload = null
        pendingCandidates.clear()
    }

    /**
     * Records our own payload once the local description exists.
     *
     * The expiry is stamped here rather than at [beginLocalDescription] so the
     * user gets the full five minutes *after* the QR code appears, which is when
     * they actually need it.
     */
    fun onLocalPayload(local: PairingPayload.Payload) {
        require(local.role == role) { "payload role ${local.role} does not match session role $role" }
        require(local.sessionId == sessionId) { "payload belongs to session ${local.sessionId}" }
        payload = local
        expiresAtMs = local.expiresAtMs
        state = if (role == PairingPayload.Role.OFFER) {
            PairingState.WAITING_FOR_GUEST
        } else {
            PairingState.WAITING_FOR_ANSWER
        }
    }

    /** Accumulates a trickled candidate until it can be re-published. */
    fun onLocalCandidate(candidate: PairingPayload.IceCandidate) {
        pendingCandidates += candidate
    }

    /** Candidates gathered so far, in discovery order. */
    fun gatheredCandidates(): List<PairingPayload.IceCandidate> = pendingCandidates.toList()

    /**
     * Host side: accepts the answer a guest scanned back.
     *
     * Rejections are specific because each one has a different remedy — a stale
     * code must be re-scanned, a foreign code means the wrong QR, a mismatched
     * room means the wrong anime.
     */
    fun acceptRemote(answer: PairingPayload.Payload, nowMs: Long = clock()): Result {
        if (role != PairingPayload.Role.OFFER) {
            return reject(Reason.WRONG_ROLE, "This device is not the host.")
        }
        if (answer.role != PairingPayload.Role.ANSWER) {
            return reject(Reason.WRONG_ROLE, "That code is an invitation, not an answer.")
        }
        if (!state.mayAcceptAnswer()) {
            return reject(
                Reason.WRONG_STATE,
                if (state == PairingState.CONNECTED) "A guest is already connected." else "No invitation is active.",
            )
        }
        if (isExpired(nowMs)) {
            return fail(
                Reason.EXPIRED,
                "That invitation has expired. Show a new code.",
                terminal = PairingState.INVITATION_EXPIRED,
            )
        }
        if (answer.roomId != roomId) {
            return reject(Reason.WRONG_ROOM, "That answer belongs to a different room.")
        }
        return when (val validation = PairingPayload.validate(answer, nowMs)) {
            is PairingPayload.Validation.Invalid -> reject(Reason.INVALID, validation.reason)
            is PairingPayload.Validation.Valid -> {
                remotePayload = validation.payload
                state = PairingState.VALIDATING_ANSWER
                Result.Accepted(validation.payload)
            }
        }
    }

    /**
     * Guest side: accepts the invitation that was scanned.
     *
     * The guest must refuse an expired code *before* it goes to the trouble of
     * creating a `PeerConnection`, so the failure is instant and obvious.
     */
    fun acceptInvitation(invitation: PairingPayload.Payload, nowMs: Long = clock()): Result {
        if (role != PairingPayload.Role.ANSWER) {
            return reject(Reason.WRONG_ROLE, "Only guests scan an invitation.")
        }
        if (invitation.role != PairingPayload.Role.OFFER) {
            return reject(Reason.WRONG_ROLE, "That code is not an invitation.")
        }
        when (val validation = PairingPayload.validate(invitation, nowMs)) {
            is PairingPayload.Validation.Invalid -> {
                val expired = nowMs >= invitation.expiresAtMs
                state = if (expired) PairingState.INVITATION_EXPIRED else PairingState.FAILED
                failureReason = validation.reason
                return Result.Rejected(if (expired) Reason.EXPIRED else Reason.INVALID, validation.reason)
            }
            is PairingPayload.Validation.Valid -> {
                remotePayload = validation.payload
                state = PairingState.CREATING_ANSWER
                return Result.Accepted(validation.payload)
            }
        }
    }

    /** Host side, after its own answer has been published to the guest. */
    fun onRemoteApplied() {
        if (role != PairingPayload.Role.OFFER) return
        remotePayload = null
        state = PairingState.ESTABLISHING
    }

    /** Guest side, after the offer was applied and its answer produced. */
    fun onAnswerPublished(answer: PairingPayload.Payload) {
        if (role != PairingPayload.Role.ANSWER) return
        onLocalPayload(answer)
        state = PairingState.WAITING_FOR_ANSWER
    }

    /** ICE/DTLS reported a usable channel. */
    fun onConnected() {
        if (state == PairingState.CONNECTED) return
        state = PairingState.CONNECTED
        failureReason = null
        // The invitation is spent; nobody should be able to scan it again.
        expiresAtMs = 0L
    }

    /**
     * Records an honest failure.
     *
     * There is no silent fallback: when direct connectivity fails, the state is
     * [PairingState.FAILED] and the reason is shown, including the advice that
     * may actually help (same Wi-Fi, or ask the host to re-create the room).
     */
    fun fail(reason: Reason, message: String, terminal: PairingState = PairingState.FAILED): Result.Rejected {
        state = terminal
        failureReason = message
        return Result.Rejected(reason, message)
    }

    /**
     * Refuses a payload **without** disturbing the session.
     *
     * A second answer arriving for an invitation that is already being
     * established is an ordinary race, not a session-ending event: the first
     * guest must stay connected. Only [fail] is allowed to move the state.
     */
    private fun reject(reason: Reason, message: String): Result.Rejected {
        failureReason = message
        return Result.Rejected(reason, message)
    }

    /** Human-readable code both sides show, so two people can confirm they paired. */
    fun verificationCode(): String? = payload?.verificationCode ?: remotePayload?.verificationCode

    /** Was this candidate gathered after the payload was built? */
    fun candidatesAfterPayload(): Boolean = payload != null && pendingCandidates.isNotEmpty()

    /** Sealed outcome of accepting a scanned payload. */
    sealed interface Result {
        data class Accepted(val payload: PairingPayload.Payload) : Result

        /**
         * The payload was refused.
         *
         * [kind] is what the code branches on; [reason] is what the user reads.
         * Every rejection carries both, so no failure can reach the UI as a
         * generic "something went wrong".
         */
        data class Rejected(val kind: Reason, val reason: String) : Result
    }

    /** Why a scanned payload was refused. Each has a different remedy. */
    enum class Reason {
        /** Code already used, or nothing is waiting for one. */
        WRONG_STATE,

        /** Scanned a host code on a host, or vice versa. */
        WRONG_ROLE,

        /** Invitation TTL elapsed. Never extended, by design. */
        EXPIRED,

        /** Answer for a different anime room. */
        WRONG_ROOM,

        /** Structurally valid but semantically wrong (clock skew, bad fields). */
        INVALID,
    }

    companion object {
        private fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "")

        /** Short, unambiguous session id used in chunk headers and verification codes. */
        fun shortId(full: String): String = full.take(12)

        private fun PairingState.mayAcceptAnswer(): Boolean =
            this == PairingState.WAITING_FOR_GUEST
    }
}
