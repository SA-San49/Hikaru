package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingPayloadTest {

    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }

    private fun payload(
        role: PairingPayload.Role = PairingPayload.Role.OFFER,
        expiresAtMs: Long = 1_000_000L,
        candidates: List<PairingPayload.IceCandidate> = listOf(
            PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "192.168.1.5", 54321),
            PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.SRFLX, "203.0.113.9", 51234),
        ),
    ) = PairingPayload.Payload(
        role = role,
        sessionId = "session-abcdef123456",
        roomId = "42:anime/solo-leveling",
        ufrag = "ufragXyz",
        pwd = "pwdLongEnoughValue123",
        fingerprint = fingerprint,
        candidates = candidates,
        expiresAtMs = expiresAtMs,
    )

    @Test
    fun `a payload round trips through encode and decode`() {
        val original = payload()
        val decoded = PairingPayload.decode(PairingPayload.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `the wire format is prefixed with the magic and version`() {
        val encoded = PairingPayload.encode(payload())
        assertTrue(encoded.startsWith(PairingPayload.MAGIC + "."), "got: ${encoded.take(12)}")
    }

    @Test
    fun `encoding is URL safe so it survives QR and paste`() {
        val encoded = PairingPayload.encode(payload())
        val body = encoded.removePrefix(PairingPayload.MAGIC + ".")
        assertTrue(body.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not url-safe: $body")
    }

    @Test
    fun `a realistic payload is far smaller than raw SDP`() {
        val encoded = PairingPayload.encode(payload())
        // The point of the compact format: comfortable for a single QR code.
        assertTrue(encoded.length < 600, "payload was ${encoded.length} chars")
    }

    @Test
    fun `foreign QR codes are rejected before any parsing`() {
        assertNull(PairingPayload.decode("https://example.com"))
        assertNull(PairingPayload.decode("WIFI:S:MyNet;T:WPA;P:secret;;"))
        assertNull(PairingPayload.decode("HP2.abc")) // wrong version
        assertNull(PairingPayload.decode(""))
        assertNull(PairingPayload.decode(null))
    }

    @Test
    fun `corrupt bodies are rejected instead of throwing`() {
        assertNull(PairingPayload.decode("HP1.not-base64!!!"))
        assertNull(PairingPayload.decode("HP1."))
        assertNull(PairingPayload.decode("HP1.QUJD")) // valid base64, not a body
    }

    @Test
    fun `oversized input is rejected`() {
        val huge = "HP1." + "A".repeat(PairingPayload.MAX_PAYLOAD_CHARS)
        assertNull(PairingPayload.decode(huge))
    }

    @Test
    fun `an expired invitation is rejected`() {
        val p = payload(expiresAtMs = 1_000_000L)
        val result = PairingPayload.validate(p, nowMs = 1_000_001L)
        assertTrue(result is PairingPayload.Validation.Invalid)
    }

    @Test
    fun `a live invitation is accepted`() {
        val p = payload(expiresAtMs = 1_000_000L)
        assertTrue(PairingPayload.validate(p, nowMs = 999_000L) is PairingPayload.Validation.Valid)
    }

    @Test
    fun `an invitation far in the future is rejected as a bad clock`() {
        val p = payload(expiresAtMs = 10_000_000L)
        val result = PairingPayload.validate(p, nowMs = 1_000_000L)
        assertTrue(result is PairingPayload.Validation.Invalid)
        assertTrue((result as PairingPayload.Validation.Invalid).reason.contains("clock"))
    }

    @Test
    fun `fresh expiry is exactly five minutes`() {
        val p = PairingPayload.withFreshExpiry(
            role = PairingPayload.Role.OFFER,
            sessionId = "session-abcdef123456",
            roomId = "42:anime/x",
            ufrag = "u",
            pwd = "p",
            fingerprint = fingerprint,
            candidates = listOf(PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "10.0.0.2", 1234)),
            nowMs = 5_000L,
        )
        assertEquals(5_000L + 5 * 60 * 1_000L, p.expiresAtMs)
    }

    @Test
    fun `a payload with no candidates is rejected`() {
        val encoded = PairingPayload.encode(payload(candidates = emptyList()))
        assertNull(PairingPayload.decode(encoded))
    }

    @Test
    fun `the verification code is short stable and checkable`() {
        val code = payload().verificationCode
        assertEquals(6, code.length)
        assertTrue(code.all { it.isLetterOrDigit() })
        assertEquals(code, payload().verificationCode)
    }
}
