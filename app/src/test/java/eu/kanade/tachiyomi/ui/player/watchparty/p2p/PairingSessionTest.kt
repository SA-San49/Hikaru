package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingSessionTest {

    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }
    private val roomId = "42:anime/solo-leveling"

    /** A guest's answer payload for [roomId]. */
    private fun answer(
        sessionId: String = "guest-session-000001",
        room: String = roomId,
        // TTL is 5 min, so this is the latest expiry a guest could honestly
        // claim when the host's clock reads 0. Anything later is clock skew,
        // which both sides must reject rather than silently accept.
        expiresAtMs: Long = 300_000L,
        role: PairingPayload.Role = PairingPayload.Role.ANSWER,
    ) = PairingPayload.Payload(
        role = role,
        sessionId = sessionId,
        roomId = room,
        ufrag = "guestUfrag",
        pwd = "guestPasswordValue",
        fingerprint = fingerprint,
        candidates = listOf(
            PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "192.168.1.9", 40000),
        ),
        expiresAtMs = expiresAtMs,
    )

    private fun hostSession(clock: () -> Long = { 0L }, generatedId: Boolean = false) =
        if (generatedId) {
            PairingSession(role = PairingPayload.Role.OFFER, roomId = roomId, clock = clock)
        } else {
            PairingSession(
                role = PairingPayload.Role.OFFER,
                roomId = roomId,
                sessionId = "host-session-00001",
                clock = clock,
            )
        }

    private fun guestSession() =
        PairingSession(role = PairingPayload.Role.ANSWER, roomId = roomId, sessionId = "guest-session-0001")

    private fun PairingSession.publishLocalOffer(expiresAtMs: Long = 900_000L) {
        beginLocalDescription()
        onLocalPayload(
            PairingPayload.Payload(
                role = PairingPayload.Role.OFFER,
                sessionId = sessionId,
                roomId = roomId,
                ufrag = "hostUfrag",
                pwd = "hostPasswordValue",
                fingerprint = fingerprint,
                candidates = listOf(
                    PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.2", 1234),
                ),
                expiresAtMs = expiresAtMs,
            ),
        )
    }

    private fun PairingSession.Result.kind(): PairingSession.Reason =
        (this as? PairingSession.Result.Rejected)?.kind ?: throw AssertionError("expected a rejection but was $this")

    @Test
    fun `a host walks from idle to waiting after publishing its offer`() {
        val session = hostSession()
        assertEquals(PairingState.IDLE, session.state)
        session.publishLocalOffer()
        assertEquals(PairingState.WAITING_FOR_GUEST, session.state)
        assertEquals(900_000L, session.expiresAtMs)
    }

    @Test
    fun `an offer must carry this session's id`() {
        val session = hostSession()
        session.beginLocalDescription()
        var threw = false
        try {
            session.onLocalPayload(
                PairingPayload.Payload(
                    role = PairingPayload.Role.OFFER,
                    sessionId = "someone-elses",
                    roomId = roomId,
                    ufrag = "u",
                    pwd = "p",
                    fingerprint = fingerprint,
                    candidates = listOf(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.2", 1)),
                    expiresAtMs = 900_000L,
                ),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a valid answer is accepted once and moves to validating`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        val result = session.acceptRemote(answer())
        assertTrue(result is PairingSession.Result.Accepted)
        assertEquals(PairingState.VALIDATING_ANSWER, session.state)
        assertEquals("guest-session-000001", session.remotePayload?.sessionId)
    }

    @Test
    fun `the same answer cannot be applied twice`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        session.acceptRemote(answer())
        val second = session.acceptRemote(answer())
        assertTrue(second is PairingSession.Result.Rejected)
        assertEquals(PairingSession.Reason.WRONG_STATE, second.kind())
        // Rejecting a second answer must not knock the session out of progress.
        assertEquals(PairingState.VALIDATING_ANSWER, session.state)
    }

    @Test
    fun `an answer with no invitation on screen is refused`() {
        val session = hostSession()
        val result = session.acceptRemote(answer())
        assertEquals(PairingSession.Reason.WRONG_STATE, result.kind())
    }

    @Test
    fun `an expired invitation cannot be answered`() {
        // Invitation dies at 300_000; the guest's answer arrives after that.
        val session = hostSession(clock = { 400_000L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        val result = session.acceptRemote(answer(expiresAtMs = 500_000L), nowMs = 400_000L)
        assertEquals(PairingSession.Reason.EXPIRED, result.kind())
        assertEquals(PairingState.INVITATION_EXPIRED, session.state)
        assertTrue(session.failureReason!!.contains("expired", ignoreCase = true))
    }

    @Test
    fun `an invitation cannot be extended by scanning later`() {
        // The guest's answer claims a fresh expiry, but the host's own invitation
        // is what governs; re-stamping the guest payload must not help.
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        session.acceptRemote(answer(expiresAtMs = 9_999_999L), nowMs = 300_001L)
        assertEquals(PairingState.INVITATION_EXPIRED, session.state)
    }

    @Test
    fun `an answer for a different anime room is refused`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        val result = session.acceptRemote(answer(room = "99:anime/other"))
        assertEquals(PairingSession.Reason.WRONG_ROOM, result.kind())
        assertTrue(session.failureReason!!.contains("different room"))
    }

    @Test
    fun `an answer that is really an invitation is refused`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        val result = session.acceptRemote(answer(role = PairingPayload.Role.OFFER))
        assertEquals(PairingSession.Reason.WRONG_ROLE, result.kind())
    }

    @Test
    fun `a guest cannot accept an answer payload`() {
        val session = guestSession()
        val result = session.acceptRemote(answer())
        assertEquals(PairingSession.Reason.WRONG_ROLE, result.kind())
    }

    @Test
    fun `a guest accepts a live invitation and starts building an answer`() {
        val session = guestSession()
        val result = session.acceptInvitation(answer(role = PairingPayload.Role.OFFER, expiresAtMs = 300_000L), nowMs = 1_000L)
        assertTrue(result is PairingSession.Result.Accepted)
        assertEquals(PairingState.CREATING_ANSWER, session.state)
    }

    @Test
    fun `a guest refuses an expired invitation before touching webrtc`() {
        val session = guestSession()
        val result = session.acceptInvitation(
            answer(role = PairingPayload.Role.OFFER, expiresAtMs = 300_000L),
            nowMs = 300_000L,
        )
        assertEquals(PairingSession.Reason.EXPIRED, result.kind())
        assertEquals(PairingState.INVITATION_EXPIRED, session.state)
    }

    @Test
    fun `a guest refuses its own kind of payload`() {
        val session = guestSession()
        val result = session.acceptInvitation(answer(), nowMs = 0L)
        assertEquals(PairingSession.Reason.WRONG_ROLE, result.kind())
    }

    @Test
    fun `connection clears the expiry so the code cannot be reused`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        session.acceptRemote(answer(), nowMs = 0L)
        session.onRemoteApplied()
        assertEquals(PairingState.ESTABLISHING, session.state)
        session.onConnected()
        assertEquals(PairingState.CONNECTED, session.state)
        assertEquals(0L, session.expiresAtMs)
        assertFalse(session.isExpired(nowMs = 999_999L))
    }

    @Test
    fun `connected is idempotent`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        session.onConnected()
        session.onConnected()
        assertEquals(PairingState.CONNECTED, session.state)
        assertNull(session.failureReason)
    }

    @Test
    fun `an answer published by the guest moves it to waiting`() {
        val session = guestSession()
        session.acceptInvitation(answer(role = PairingPayload.Role.OFFER, expiresAtMs = 300_000L), nowMs = 0L)
        val guestAnswer = PairingPayload.Payload(
            role = PairingPayload.Role.ANSWER,
            sessionId = session.sessionId,
            roomId = roomId,
            ufrag = "u",
            pwd = "p",
            fingerprint = fingerprint,
            candidates = listOf(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.3", 2222)),
            expiresAtMs = 300_000L,
        )
        session.onAnswerPublished(guestAnswer)
        assertEquals(PairingState.WAITING_FOR_ANSWER, session.state)
    }

    @Test
    fun `candidates are accumulated in discovery order`() {
        val session = hostSession()
        session.publishLocalOffer()
        session.onLocalCandidate(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.2", 1111))
        session.onLocalCandidate(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.SRFLX, "203.0.113.4", 2222))
        assertEquals(2, session.gatheredCandidates().size)
        assertEquals("10.0.0.2", session.gatheredCandidates().first().address)
        assertTrue(session.candidatesAfterPayload())
    }

    @Test
    fun `seconds remaining counts down and floors at zero`() {
        val session = hostSession()
        session.publishLocalOffer(expiresAtMs = 300_000L)
        assertEquals(300L, session.secondsRemaining(nowMs = 0L))
        assertEquals(300L, session.secondsRemaining(nowMs = 1L))
        assertEquals(1L, session.secondsRemaining(nowMs = 299_500L))
        // Still scannable, so it must not read 00:00 yet.
        assertEquals(1L, session.secondsRemaining(nowMs = 299_999L))
        assertEquals(0L, session.secondsRemaining(nowMs = 300_000L))
        assertEquals(0L, session.secondsRemaining(nowMs = 999_999L))
    }

    @Test
    fun `a session that never published has no countdown`() {
        assertEquals(0L, hostSession().secondsRemaining(nowMs = 0L))
        assertFalse(hostSession().isExpired(nowMs = 99_999_999L))
    }

    @Test
    fun `the verification code comes from whichever side is known`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        assertEquals(6, session.verificationCode()!!.length)
        val hostCode = session.verificationCode()
        session.acceptRemote(answer())
        // Still the host's own code; the guest matches it against its screen.
        assertEquals(hostCode, session.verificationCode())
    }

    @Test
    fun `expiry only applies while an invitation is still waiting`() {
        // The TTL governs how long the QR stays valid, not how long ICE and DTLS
        // may take. A session that has accepted an answer is no longer "waiting",
        // so its TTL must stop mattering.
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        assertTrue(session.state == PairingState.WAITING_FOR_GUEST)

        val result = session.acceptRemote(answer(), nowMs = 299_000L)
        assertTrue("answer should be accepted, was $result", result is PairingSession.Result.Accepted)
        assertEquals(PairingState.VALIDATING_ANSWER, session.state)

        // Past the original expiry, the session has moved on; the host's ticker
        // must not treat it as an expired invitation.
        assertFalse(session.state == PairingState.WAITING_FOR_GUEST)
    }

    @Test
    fun `an unanswered invitation does expire`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        assertTrue(session.state == PairingState.WAITING_FOR_GUEST)
        assertTrue(session.isExpired(nowMs = 300_000L))
    }

    @Test
    fun `a failed session can be restarted without leaking the old invitation`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer(expiresAtMs = 300_000L)
        session.fail(PairingSession.Reason.EXPIRED, "expired")
        assertEquals(PairingState.FAILED, session.state)
        session.beginLocalDescription()
        assertEquals(PairingState.CREATING_INVITATION, session.state)
        assertEquals(0L, session.expiresAtMs)
        assertNull(session.failureReason)
        assertNull(session.payload)
    }

    @Test
    fun `a connected session cannot be restarted in place`() {
        val session = hostSession(clock = { 0L })
        session.publishLocalOffer()
        session.onConnected()
        var threw = false
        try {
            session.beginLocalDescription()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a guest can start creating its answer right after accepting an invitation`() {
        val session = guestSession()
        val result = session.acceptInvitation(
            answer(role = PairingPayload.Role.OFFER, expiresAtMs = 300_000L),
            nowMs = 1_000L,
        )
        assertTrue(result is PairingSession.Result.Accepted)
        assertEquals(PairingState.CREATING_ANSWER, session.state)

        // Regression test for crash: P2PSession.createAnswerFor calls beginLocalDescription()
        // right after P2PGuestCoordinator.beginPairing called acceptInvitation().
        session.beginLocalDescription()
        assertEquals(PairingState.CREATING_ANSWER, session.state)
        assertTrue(session.remotePayload != null)

        val guestAnswer = PairingPayload.Payload(
            role = PairingPayload.Role.ANSWER,
            sessionId = session.sessionId,
            roomId = roomId,
            ufrag = "u",
            pwd = "p",
            fingerprint = fingerprint,
            candidates = listOf(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.3", 2222)),
            expiresAtMs = 300_000L,
        )
        session.onAnswerPublished(guestAnswer)
        assertEquals(PairingState.WAITING_FOR_ANSWER, session.state)
    }

    @Test
    fun `a connected guest session still cannot be restarted in place`() {
        val session = guestSession()
        session.acceptInvitation(answer(role = PairingPayload.Role.OFFER, expiresAtMs = 300_000L), nowMs = 1_000L)
        session.beginLocalDescription()
        session.onConnected()
        var threw = false
        try {
            session.beginLocalDescription()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `each session gets a distinct id so offers are never shared`() {
        val ids = (1..50).map { hostSession(generatedId = true).sessionId }
        assertEquals(50, ids.toSet().size, "session ids were reused")
        assertTrue(ids.all { it.length >= 16 })
    }
}
