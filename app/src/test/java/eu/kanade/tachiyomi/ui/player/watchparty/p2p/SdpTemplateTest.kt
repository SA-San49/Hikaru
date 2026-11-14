package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdpTemplateTest {

    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }

    private fun payload(role: PairingPayload.Role) = PairingPayload.Payload(
        role = role,
        sessionId = "session-abcdef123456",
        roomId = "42:anime/x",
        ufrag = "ufragXyz",
        pwd = "pwdLongEnoughValue123",
        fingerprint = fingerprint,
        candidates = listOf(
            PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "192.168.1.5", 54321),
        ),
        expiresAtMs = 1_000_000L,
    )

    @Test
    fun `an offer rebuilds with actpass and its credentials`() {
        val sdp = SdpTemplate.build(payload(PairingPayload.Role.OFFER))
        assertTrue(sdp.contains("a=ice-ufrag:ufragXyz"))
        assertTrue(sdp.contains("a=ice-pwd:pwdLongEnoughValue123"))
        assertTrue(sdp.contains("a=fingerprint:sha-256 $fingerprint"))
        assertTrue(sdp.contains("a=setup:actpass"))
    }

    @Test
    fun `an answer rebuilds as the active DTLS side`() {
        val sdp = SdpTemplate.build(payload(PairingPayload.Role.ANSWER))
        assertTrue(sdp.contains("a=setup:active"))
    }

    @Test
    fun `no media section is ever generated`() {
        val sdp = SdpTemplate.build(payload(PairingPayload.Role.OFFER))
        assertTrue(!sdp.contains("m=audio"), "audio m-line present")
        assertTrue(!sdp.contains("m=video"), "video m-line present")
        assertEquals(1, sdp.lineSequence().count { it.startsWith("m=") })
        assertTrue(sdp.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"))
    }

    @Test
    fun `the rebuilt sdp round trips through parse`() {
        val original = payload(PairingPayload.Role.OFFER)
        val parsed = SdpTemplate.parse(SdpTemplate.build(original))
        assertTrue(parsed != null)
        parsed!!
        assertEquals(original.ufrag, parsed.ufrag)
        assertEquals(original.pwd, parsed.pwd)
        assertEquals(original.fingerprint, parsed.fingerprint)
        assertEquals(original.candidates, parsed.candidates)
    }

    @Test
    fun `parse reads a stock chromium data channel sdp`() {
        // Shape emitted by org.webrtc for a DataChannel-only offer.
        val sdp = """
            v=0
            o=- 4611731400430051336 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            c=IN IP4 0.0.0.0
            a=ice-ufrag:4ZcD
            a=ice-pwd:2/1muCWoOi3uLifh0gQ7Y3jW
            a=ice-options:trickle
            a=fingerprint:sha-256 6B:8B:F0:65:5F:78:E2:51:3B:AC:6F:F3:3F:46:1B:35:DC:B8:5F:64:1A:24:C2:43:F0:A1:58:D0:A1:2C:19:08
            a=setup:actpass
            a=mid:0
            a=sctp-port:5000
            a=max-message-size:262144
            a=candidate:842163049 1 udp 1677729535 203.0.113.9 51234 typ srflx raddr 192.168.1.5 rport 54321 generation 0
            a=candidate:1510613869 1 udp 2122260223 192.168.1.5 54321 typ host generation 0
        """.trimIndent()

        val parsed = SdpTemplate.parse(sdp)!!
        assertEquals("4ZcD", parsed.ufrag)
        assertEquals("2/1muCWoOi3uLifh0gQ7Y3jW", parsed.pwd)
        assertTrue(parsed.fingerprint.startsWith("6B:8B"))
        assertEquals(2, parsed.candidates.size)
        assertEquals(PairingPayload.IceCandidate.Type.SRFLX, parsed.candidates[0].type)
        assertEquals("203.0.113.9", parsed.candidates[0].address)
        assertEquals(51234, parsed.candidates[0].port)
        assertEquals(PairingPayload.IceCandidate.Type.HOST, parsed.candidates[1].type)
    }

    @Test
    fun `parse tolerates relay and prflx candidates`() {
        val sdp = """
            a=ice-ufrag:u
            a=ice-pwd:p
            a=fingerprint:sha-256 $fingerprint
            a=candidate:1 1 udp 1 198.51.100.7 3478 typ relay raddr 0.0.0.0 rport 0
            a=candidate:2 1 udp 1 198.51.100.8 40000 typ prflx
        """.trimIndent()
        val parsed = SdpTemplate.parse(sdp)!!
        assertEquals(
            listOf(
                PairingPayload.IceCandidate.Type.RELAY,
                PairingPayload.IceCandidate.Type.PRFLX,
            ),
            parsed.candidates.map { it.type },
        )
    }

    @Test
    fun `parse rejects a description missing required attributes`() {
        assertNull(SdpTemplate.parse(null))
        assertNull(SdpTemplate.parse(""))
        assertNull(SdpTemplate.parse("v=0\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel"))
        // ufrag + pwd but no fingerprint
        assertNull(SdpTemplate.parse("a=ice-ufrag:u\na=ice-pwd:p"))
    }

    @Test
    fun `malformed candidate lines are dropped not fatal`() {
        val sdp = """
            a=ice-ufrag:u
            a=ice-pwd:p
            a=fingerprint:sha-256 $fingerprint
            a=candidate:1 1 udp
            a=candidate:2 1 udp 1 10.0.0.1 notaport typ host
            a=candidate:3 1 udp 1 10.0.0.2 1234 typ bogus
            a=candidate:4 1 udp 1 10.0.0.3 1234 typ host
        """.trimIndent()
        val parsed = SdpTemplate.parse(sdp)!!
        assertEquals(1, parsed.candidates.size)
        assertEquals("10.0.0.3", parsed.candidates.single().address)
    }

    @Test
    fun `an ipv6 candidate survives the round trip`() {
        val p = payload(PairingPayload.Role.OFFER).copy(
            candidates = listOf(
                PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "fe80::1c2d:3e4f:5a6b:7c8d", 54321),
            ),
        )
        val parsed = SdpTemplate.parse(SdpTemplate.build(p))!!
        assertEquals("fe80::1c2d:3e4f:5a6b:7c8d", parsed.candidates.single().address)
    }

    @Test
    fun `srflx and relay candidates emit raddr and rport while host does not`() {
        val p = payload(PairingPayload.Role.OFFER).copy(
            candidates = listOf(
                PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "192.168.1.5", 54321),
                PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.SRFLX, "203.0.113.9", 51234),
                PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.RELAY, "2001:db8::1", 3478),
            ),
        )
        val sdpLines = SdpTemplate.build(p).lines()
        assertTrue(sdpLines.any { it.endsWith("192.168.1.5 54321 typ host") })
        assertTrue(sdpLines.any { it.endsWith("203.0.113.9 51234 typ srflx raddr 0.0.0.0 rport 0") })
        assertTrue(sdpLines.any { it.endsWith("2001:db8::1 3478 typ relay raddr :: rport 0") })
    }

    @Test
    fun `parse ignores non-udp candidates and handles uppercase SHA-256 fingerprint`() {
        val sdp = """
            a=ice-ufrag:u
            a=ice-pwd:p
            a=fingerprint:SHA-256 $fingerprint
            a=candidate:1 1 tcp 1518280447 192.168.1.5 9 typ host tcptype active
            a=candidate:2 1 udp 2122260223 192.168.1.5 54321 typ host
        """.trimIndent()
        val parsed = SdpTemplate.parse(sdp)!!
        assertEquals(fingerprint, parsed.fingerprint)
        assertEquals(1, parsed.candidates.size)
        assertEquals(54321, parsed.candidates.single().port)
    }
}
