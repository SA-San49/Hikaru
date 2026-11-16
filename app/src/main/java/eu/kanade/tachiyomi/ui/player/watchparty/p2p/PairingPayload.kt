package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import java.security.MessageDigest
import java.util.Base64

/**
 * Compact, versioned pairing payload exchanged out-of-band (QR or pasted text).
 *
 * ## Why not just send the SDP
 *
 * A DataChannel-only SDP is 2.5–4 KB. That is too large for a comfortably
 * scannable QR code and miserable to copy by hand. Almost all of that is
 * boilerplate that both sides can reconstruct identically, so only the parts
 * that actually differ are transmitted:
 *
 *  - ICE `ufrag` and `pwd`;
 *  - the DTLS certificate fingerprint;
 *  - the gathered ICE candidates;
 *  - a session id, a protocol version and an expiry.
 *
 * Everything else is rebuilt by [SdpTemplate] from a fixed template. This
 * follows the principle of the QWBP specification (MIT, Martin Garcia
 * Monterde) — see `docs/watch-party-p2p.md` — without adopting its byte layout
 * or its non-standard ICE credential derivation, so ordinary WebRTC peers can
 * still interoperate.
 *
 * ## Wire format
 *
 * Text, because QR codes are text and pasting is a required fallback:
 *
 * ```
 * HP1.<base64url(deflate(body))>
 * ```
 *
 * `HP1` is the magic + version. It lets [decode] reject a foreign QR code (a
 * menu, a URL, a ticket) before doing any real work.
 *
 * The body is a list of `;`-separated fields; candidates are `|`-separated.
 * Base64url keeps the string QR-safe and paste-safe (no `+`, no `/`).
 */
object PairingPayload {

    /** Magic + protocol version. Bump the version on any breaking change. */
    const val MAGIC = "HP1"

    /** Hard ceiling on an accepted payload, well above any real invitation. */
    const val MAX_PAYLOAD_CHARS = 8_000

    /** Invitations are valid for exactly five minutes. */
    const val INVITATION_TTL_MS = 5 * 60 * 1_000L

    enum class Role {
        OFFER,
        ANSWER,
    }

    /**
     * A single ICE candidate, compacted.
     *
     * `type` is stored as a one-letter code; `foundation` and `priority` are
     * re-derived where the platform allows it, since only the host needs a
     * stable priority ordering.
     */
    data class IceCandidate(
        val type: Type,
        val address: String,
        val port: Int,
    ) {
        enum class Type(val code: Char) {
            HOST('h'),
            SRFLX('s'),
            PRFLX('p'),
            RELAY('r'),
            ;

            companion object {
                fun fromCode(code: Char): Type? = entries.firstOrNull { it.code == code }
            }
        }
    }

    /**
     * A decoded, validated pairing payload.
     *
     * @param sessionId random per pairing attempt; also the replay guard.
     * @param roomId anime-level room identity, so the guest joins the right room
     *   without needing a separate code exchange.
     * @param expiresAtMs absolute wall-clock expiry (encoded, so a guest can
     *   reject an obviously stale invitation even before contacting the host).
     */
    data class Payload(
        val role: Role,
        val sessionId: String,
        val roomId: String,
        val ufrag: String,
        val pwd: String,
        val fingerprint: String,
        val candidates: List<IceCandidate>,
        val expiresAtMs: Long,
    ) {
        /** Short human-checkable code, so two people can confirm they scanned the right screen. */
        val verificationCode: String
            get() = sessionId.filter { it.isLetterOrDigit() }.takeLast(6).uppercase().padEnd(6, '0')
    }

    /** Result of validating a decoded payload against the local clock. */
    sealed interface Validation {
        data class Valid(val payload: Payload) : Validation
        data class Invalid(val reason: String) : Validation
    }

    /**
     * Encodes [payload] into the paste-safe wire string.
     *
     * The body is deflated: repeated SDP boilerplate and candidate prefixes
     * compress well, typically cutting the payload by half.
     */
    fun encode(payload: Payload): String {
        val body = buildString {
            append(payload.role.name.first())
            append(';').append(payload.sessionId)
            append(';').append(payload.roomId)
            append(';').append(payload.ufrag)
            append(';').append(payload.pwd)
            append(';').append(payload.fingerprint)
            append(';').append(payload.expiresAtMs)
            append(';').append(
                payload.candidates.joinToString("|") { "${it.type.code},${it.address},${it.port}" },
            )
        }
        return "$MAGIC.${Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(body.toByteArray()))}"
    }

    /**
     * Decodes and structurally validates a scanned/pasted string.
     *
     * Returns `null` for anything that is not a well-formed Hikaru pairing
     * payload: wrong magic, corrupt base64, truncated body, missing fields,
     * unparseable numbers. It never throws, because callers feed it raw camera
     * input.
     */
    fun decode(raw: String?): Payload? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > MAX_PAYLOAD_CHARS) return null
        if (!trimmed.startsWith("$MAGIC.")) return null

        val encoded = trimmed.removePrefix("$MAGIC.")
        if (encoded.isEmpty()) return null

        val body = runCatching {
            String(inflate(Base64.getUrlDecoder().decode(encoded)))
        }.getOrNull() ?: return null

        return runCatching { parseBody(body) }.getOrNull()
    }

    private fun parseBody(body: String): Payload? {
        val fields = body.split(';')
        if (fields.size < 8) return null

        val role = when (fields[0]) {
            "O" -> Role.OFFER
            "A" -> Role.ANSWER
            else -> return null
        }
        val sessionId = fields[1].takeIf { it.length in 8..64 } ?: return null
        val roomId = fields[2].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val ufrag = fields[3].takeIf { it.length in 1..256 } ?: return null
        val pwd = fields[4].takeIf { it.length in 1..256 } ?: return null
        val fingerprint = fields[5].takeIf { isFingerprint(it) } ?: return null
        val expiresAtMs = fields[6].toLongOrNull() ?: return null
        if (expiresAtMs <= 0) return null

        val candidates = if (fields[7].isBlank()) {
            emptyList()
        } else {
            fields[7].split('|').mapNotNull { parseCandidate(it) }
        }
        // A host with no candidates at all can never be reached; an answer with
        // none is likewise useless. Treat that as a malformed payload.
        if (candidates.isEmpty()) return null

        return Payload(
            role = role,
            sessionId = sessionId,
            roomId = roomId,
            ufrag = ufrag,
            pwd = pwd,
            fingerprint = fingerprint,
            candidates = candidates,
            expiresAtMs = expiresAtMs,
        )
    }

    private fun parseCandidate(field: String): IceCandidate? {
        val parts = field.split(',')
        if (parts.size != 3) return null
        val type = parts[0].takeIf { it.length == 1 }?.firstOrNull()?.let(IceCandidate.Type::fromCode) ?: return null
        val address = parts[1].takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return IceCandidate(type, address, port)
    }

    /** DTLS fingerprints are `AA:BB:...` hex pairs (SHA-256). */
    private fun isFingerprint(value: String): Boolean {
        val parts = value.split(':')
        if (parts.size != 32) return false
        return parts.all { it.length == 2 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    }

    /**
     * Validates [payload] against [nowMs].
     *
     * Expiry uses an absolute wall-clock value so it cannot be extended by
     * backgrounding the app or by scanning slowly. The caller passes its own
     * clock, so tests do not need to sleep for five minutes.
     */
    fun validate(payload: Payload, nowMs: Long): Validation {
        if (payload.expiresAtMs <= nowMs) {
            return Validation.Invalid("This invitation has expired. Ask the host for a new one.")
        }
        // A little clock skew between two devices is unavoidable; reject only
        // invitations that are implausibly far in the future.
        if (payload.expiresAtMs - nowMs > INVITATION_TTL_MS + MAX_CLOCK_SKEW_MS) {
            return Validation.Invalid("This invitation is not valid yet. Check both devices' clocks.")
        }
        return Validation.Valid(payload)
    }

    /** Builds a payload whose invitation dies exactly [INVITATION_TTL_MS] later. */
    fun withFreshExpiry(
        role: Role,
        sessionId: String,
        roomId: String,
        ufrag: String,
        pwd: String,
        fingerprint: String,
        candidates: List<IceCandidate>,
        nowMs: Long,
    ): Payload = Payload(
        role = role,
        sessionId = sessionId,
        roomId = roomId,
        ufrag = ufrag,
        pwd = pwd,
        fingerprint = fingerprint,
        candidates = candidates,
        expiresAtMs = nowMs + INVITATION_TTL_MS,
    )

    /** SHA-256 of the payload body, used to reject a swapped payload mid-exchange. */
    fun fingerprintOf(payload: Payload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(
            "${payload.sessionId}|${payload.roomId}|${payload.ufrag}|${payload.fingerprint}"
                .toByteArray(),
        )
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1_000L

    private fun deflate(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(input) }
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.InflaterInputStream(input.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }
}
