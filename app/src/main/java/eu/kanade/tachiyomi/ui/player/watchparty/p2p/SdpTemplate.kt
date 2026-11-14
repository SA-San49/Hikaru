package eu.kanade.tachiyomi.ui.player.watchparty.p2p

/**
 * Converts between the compact [PairingPayload] and the full SDP that
 * `org.webrtc` insists on.
 *
 * ## Why this exists
 *
 * A DataChannel-only SDP is 2.5–4 KB of text that is identical on every device
 * except for a handful of fields. Sending the whole document means a QR code too
 * dense to scan, and a copy-paste string far beyond what anyone will type
 * correctly.
 *
 * The split of responsibility is therefore:
 *
 *  - **`parse`** — we ask our own `PeerConnection` for its description
 *    (`createOffer`/`createAnswer`), then keep only the volatile fields
 *    (ICE credentials, DTLS fingerprint, candidates) and throw the boilerplate
 *    away. Those fields go into a [PairingPayload] and travel in the QR code.
 *  - **`build`** — the peer receives that payload and rebuilds a *complete*
 *    SDP locally, which is what `setRemoteDescription` requires. Both sides
 *    agree on this template, so the reconstruction is exact.
 *
 * This follows the principle behind the QWBP specification (MIT, Martin Garcia
 * Monterde) — transmit the difference, not the document — while keeping the
 * output standards-compliant so an ordinary WebRTC peer could still join. See
 * `docs/watch-party-p2p.md` for why QWBP's non-standard ICE credential
 * derivation was *not* adopted.
 *
 * ## No media, ever
 *
 * The template contains exactly one `m=` line, and it is `application`. Media
 * sections are never synthesised, so "no video over the peer connection" is a
 * structural property of the signalling rather than a discipline the rest of
 * the code has to maintain. Guests keep streaming through their own source
 * extensions; Watch Party only ever carries signalling, sync and chat.
 */
object SdpTemplate {

    /**
     * Chromium's SCTP pseudo-port. Fixed for every WebRTC implementation; only
     * the `sctp-port` attribute references it.
     */
    private const val SCTP_PORT = 5000

    /** Largest message the data channel will accept, per RFC 8841 default. */
    private const val MAX_MESSAGE_SIZE = 262144

    /**
     * Session id/version. Arbitrary by spec; fixed here because both sides
     * rebuild the same text and there is no reason to vary it.
     */
    private const val SESSION_ID = "4611731400430051336"
    private const val SESSION_VERSION = "2"

    /**
     * Rebuilds a full SDP from [payload].
     *
     * The result is a *remote* description: it describes the peer that produced
     * the payload. `setup` is implied by the role — an offerer must offer
     * `actpass` so the answerer can choose a DTLS role, and an answerer replies
     * `active`.
     */
    fun build(payload: PairingPayload.Payload): String {
        val setup = when (payload.role) {
            PairingPayload.Role.OFFER -> "actpass"
            PairingPayload.Role.ANSWER -> "active"
        }
        return buildString {
            // Session block. `a=group:BUNDLE 0` is required by the SDP grammar
            // even with a single m-line, and some stacks reject its absence.
            appendLine("v=0")
            appendLine("o=- $SESSION_ID $SESSION_VERSION IN IP4 127.0.0.1")
            appendLine("s=-")
            appendLine("t=0 0")
            appendLine("a=group:BUNDLE 0")
            appendLine("a=extmap-allow-mixed")
            appendLine("a=msid-semantic: WMS")

            // The one and only m-line: a DataChannel. No audio, no video.
            appendLine("m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("a=ice-ufrag:${payload.ufrag}")
            appendLine("a=ice-pwd:${payload.pwd}")
            appendLine("a=ice-options:trickle")
            appendLine("a=fingerprint:sha-256 ${payload.fingerprint}")
            appendLine("a=setup:$setup")
            appendLine("a=mid:0")
            appendLine("a=sctp-port:$SCTP_PORT")
            appendLine("a=max-message-size:$MAX_MESSAGE_SIZE")

            // Candidates gathered before pairing. Later ones trickle in over the
            // data channel once it opens.
            payload.candidates.forEach { appendLine(candidateLine(it)) }
        }
    }

    /**
     * Extracts the fields Hikaru needs from a locally created SDP.
     *
     * Returns `null` when a required attribute is missing, which means the
     * local stack produced something this template cannot represent — the
     * caller must surface that as a pairing failure rather than send a payload
     * the peer could not rebuild.
     */
    fun parse(sdp: String?): Parsed? {
        if (sdp.isNullOrBlank()) return null
        val ufrag = sdp.firstValueAfter("a=ice-ufrag:") ?: return null
        val pwd = sdp.firstValueAfter("a=ice-pwd:") ?: return null
        val fingerprint = sdp.firstValueAfter("a=fingerprint:sha-256 ", ignoreCase = true) ?: return null
        val candidates = sdp.linesWithPrefix("a=candidate:").mapNotNull(::parseCandidateLine)
        return Parsed(ufrag = ufrag, pwd = pwd, fingerprint = fingerprint, candidates = candidates)
    }

    /** The subset of SDP that actually varies between two devices. */
    data class Parsed(
        val ufrag: String,
        val pwd: String,
        val fingerprint: String,
        val candidates: List<PairingPayload.IceCandidate>,
    )

    /** `a=candidate:1 1 udp 2130706431 192.168.1.5 54321 typ host ...` */
    private fun candidateLine(candidate: PairingPayload.IceCandidate): String {
        val type = when (candidate.type) {
            PairingPayload.IceCandidate.Type.HOST -> "host"
            PairingPayload.IceCandidate.Type.SRFLX -> "srflx"
            PairingPayload.IceCandidate.Type.PRFLX -> "prflx"
            PairingPayload.IceCandidate.Type.RELAY -> "relay"
        }
        // Foundation, component and priority are placeholders: the receiving
        // stack recomputes priority itself during the connectivity check, and
        // never reads the foundation back.
        // Non-host candidates (srflx/prflx/relay) require `raddr ... rport ...`
        // for Chromium's libwebrtc SDP parser to accept them.
        val base = "a=candidate:1 1 udp 2130706431 ${candidate.address} ${candidate.port} typ $type"
        if (candidate.type == PairingPayload.IceCandidate.Type.HOST) return base
        val raddr = if (candidate.address.contains(':')) "::" else "0.0.0.0"
        return "$base raddr $raddr rport 0"
    }

    private fun parseCandidateLine(line: String): PairingPayload.IceCandidate? {
        val parts = line.removePrefix("a=candidate:").trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 8) return null
        if (!parts[2].equals("udp", ignoreCase = true)) return null
        val address = parts[4].takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val port = parts[5].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        val typeIndex = parts.indexOfFirst { it.equals("typ", ignoreCase = true) }
        if (typeIndex < 0 || typeIndex + 1 >= parts.size) return null
        val type = when (parts[typeIndex + 1].lowercase()) {
            "host" -> PairingPayload.IceCandidate.Type.HOST
            "srflx" -> PairingPayload.IceCandidate.Type.SRFLX
            "prflx" -> PairingPayload.IceCandidate.Type.PRFLX
            "relay" -> PairingPayload.IceCandidate.Type.RELAY
            else -> return null
        }
        return PairingPayload.IceCandidate(type, address, port)
    }

    private fun String.firstValueAfter(prefix: String, ignoreCase: Boolean = false): String? {
        val line = lineSequence().firstOrNull { it.startsWith(prefix, ignoreCase = ignoreCase) } ?: return null
        return line.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    }

    private fun String.linesWithPrefix(prefix: String): List<String> =
        lineSequence().filter { it.startsWith(prefix, ignoreCase = true) }.toList()
}
