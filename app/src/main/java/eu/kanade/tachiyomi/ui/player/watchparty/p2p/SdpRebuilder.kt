package eu.kanade.tachiyomi.ui.player.watchparty.p2p

/**
 * Turns a rebuilt SDP into the same shape `org.webrtc`'s parser expects, and
 * repairs one place where it is stricter than the RFC.
 *
 * Kept apart from [SdpTemplate] so the text template stays free of platform
 * quirks and can be tested on the JVM.
 */
object SdpRebuilder {

    /**
     * `SessionDescription.Type` as a plain enum, so this file does not depend on
     * the WebRTC SDK and can still be reasoned about in isolation.
     */
    enum class Type {
        OFFER,
        ANSWER,
    }

    /** A description ready to hand to `PeerConnection.setRemoteDescription`. */
    data class Description(
        val type: Type,
        val sdp: String,
    )

    /**
     * Rewrites the `a=setup:` line of [sdp] for the given side.
     *
     * The template already emits the correct value; this exists because a peer
     * may hand us raw SDP (paste fallback) whose role we must override to avoid
     * both sides claiming `actpass`, which produces a DTLS role clash where
     * neither end starts the handshake.
     */
    fun withSetup(sdp: String, setup: String): String =
        sdp.lineSequence()
            .map { line ->
                if (line.startsWith("a=setup:")) "a=setup:$setup" else line
            }
            .joinToString("\n")

    /**
     * Describes what a [PairingPayload.Payload] should be applied *as*.
     *
     * A payload produced by the host is an offer; one produced by a guest is an
     * answer. This is not cosmetic — applying an offer as an answer throws.
     */
    fun typeFor(payload: PairingPayload.Payload): Type = when (payload.role) {
        PairingPayload.Role.OFFER -> Type.OFFER
        PairingPayload.Role.ANSWER -> Type.ANSWER
    }

    /** Builds the remote description for [payload]. */
    fun remoteDescriptionFor(payload: PairingPayload.Payload): Description =
        Description(type = typeFor(payload), sdp = SdpTemplate.build(payload))
}
