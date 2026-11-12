package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * One direct WebRTC connection to one peer.
 *
 * ## Scope
 *
 * Exactly one `PeerConnection` per guest, created on demand and destroyed on
 * disconnect — never recycled, never shared. An offer is single-use by
 * construction: a new guest means a new [P2PSession] with a new session id, new
 * ICE credentials and a new DTLS certificate, so a stale QR code can never be
 * replayed into an existing connection.
 *
 * ## What travels over it
 *
 * One DataChannel carrying the same JSON frames the relay transport uses.
 * **No audio or video track is ever added.** Guests stream from their own
 * source extensions, so nothing here touches media, and no code path exists
 * that could turn this into a screen-share session.
 *
 * ## Honest failure
 *
 * There is no TURN server and no silent relay fallback. If ICE cannot produce a
 * working pair — typically because both peers are behind symmetric NAT — the
 * session moves to [PairingState.FAILED] with a reason that names the likely
 * cause. Silently reconnecting through a server Hikaru does not operate would
 * be a lie about how the feature works.
 */
class P2PSession(
    val pairing: PairingSession,
    private val factoryHolder: PeerConnectionFactoryHolder,
    private val onEvent: Event,
) {

    /** Things this session reports outwards. */
    interface Event {
        /** Our own offer/answer is ready; a payload can now be published. */
        fun onLocalDescription(session: P2PSession, parsed: SdpTemplate.Parsed)

        /** A new local candidate was gathered after the payload was published. */
        fun onLocalCandidate(session: P2PSession, candidate: PairingPayload.IceCandidate)

        /** ICE/DTLS is usable; the data channel is open. */
        fun onConnected(session: P2PSession)

        /** The connection is gone; the peer must be dropped from the room. */
        fun onDisconnected(session: P2PSession, reason: String)

        /** A text frame arrived from the peer. */
        fun onMessage(session: P2PSession, text: String)
    }

    /** Labels the single data channel; also its identity in `onDataChannel`. */
    val channelLabel: String = CHANNEL_LABEL

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    /** Remote description applied? Guards against double `setRemoteDescription`. */
    private var remoteApplied = false

    val isConnected: Boolean get() = pairing.state == PairingState.CONNECTED

    // <editor-fold desc="Host side">

    /**
     * Host: creates the offer and starts gathering candidates.
     *
     * Called once per guest. The resulting payload is published via
     * [Event.onLocalDescription] and rendered as a QR code.
     */
    fun createOffer() {
        if (pairing.role != PairingPayload.Role.OFFER) {
            onEvent.onDisconnected(this, "This device is not the host.")
            return
        }
        pairing.beginLocalDescription()
        val pc = createPeerConnection() ?: return
        // The channel is created before the offer so that the m-line exists;
        // SCTP negotiates in-band afterwards.
        dataChannel = pc.createDataChannel(CHANNEL_LABEL, DataChannel.Init().apply { ordered = true })
            .also(::wireDataChannel)

        pc.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    pc.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                publishLocalDescription(description.description)
                            }

                            override fun onSetFailure(error: String) {
                                failHard("Could not prepare the invitation: $error")
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) {
                    failHard("Could not create an invitation: $error")
                }
            },
            dataChannelConstraints(),
        )
    }

    /**
     * Host: applies the answer scanned back from the guest.
     *
     * The session is the authority on whether the answer is acceptable; this
     * method only translates that decision into PeerConnection calls.
     */
    fun applyAnswer(payload: PairingPayload.Payload) {
        val pc = peerConnection ?: run {
            failHard("The invitation is no longer active.")
            return
        }
        if (remoteApplied) {
            failHard("That answer was already used. Show a new invitation.")
            return
        }
        val remote = SdpRebuilder.remoteDescriptionFor(payload)
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    remoteApplied = true
                    // Later candidates can now be applied instead of buffered.
                    drainPendingRemoteCandidates()
                    pairing.onRemoteApplied()
                }

                override fun onSetFailure(error: String) {
                    failHard("That answer could not be used: $error")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, remote.sdp),
        )
    }

    // </editor-fold>

    // <editor-fold desc="Guest side">

    /**
     * Guest: applies the invitation, then produces the answer to scan back.
     *
     * Both `setRemoteDescription` and `createAnswer` are asynchronous, so the
     * second is chained from the first rather than assumed to follow it.
     */
    fun createAnswerFor(invitation: PairingPayload.Payload) {
        if (pairing.role != PairingPayload.Role.ANSWER) {
            onEvent.onDisconnected(this, "Only guests scan an invitation.")
            return
        }
        pairing.beginLocalDescription()
        val pc = createPeerConnection() ?: return
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    remoteApplied = true
                    drainPendingRemoteCandidates()
                    createAnswer(pc)
                }

                override fun onSetFailure(error: String) {
                    failHard("That invitation could not be used: $error")
                }
            },
            SessionDescription(
                SessionDescription.Type.OFFER,
                SdpTemplate.build(invitation),
            ),
        )
    }

    private fun createAnswer(pc: PeerConnection) {
        pc.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    pc.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                publishLocalDescription(description.description)
                            }

                            override fun onSetFailure(error: String) {
                                failHard("Could not prepare the answer: $error")
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) {
                    failHard("Could not create an answer: $error")
                }
            },
            dataChannelConstraints(),
        )
    }

    // </editor-fold>

    // <editor-fold desc="Shared">

    /**
     * Publishes our own description as a compact payload.
     *
     * [SdpTemplate.parse] is the gate: if the local stack emitted something this
     * format cannot represent, we refuse to publish rather than send a payload
     * the peer will fail to rebuild. That failure mode is reported, never papered
     * over with a raw-SDP code too large to scan.
     */
    private fun publishLocalDescription(sdp: String) {
        val parsed = SdpTemplate.parse(sdp)
        if (parsed == null) {
            failHard("This device produced an unsupported connection description.")
            return
        }
        onEvent.onLocalDescription(this, parsed)
    }

    /**
     * The open channel, or `null` before it exists.
     *
     * Exposed so the link adapter can write to it and read its state, without
     * handing out the `PeerConnection` itself.
     */
    fun dataChannel(): DataChannel? = dataChannel

    /** True while this session holds a factory reference. */
    private var retained = false

    /**
     * Stops the session and releases the native peer connection.
     *
     * Idempotent: the host calls it when a guest leaves, the player calls it on
     * teardown, and a failed handshake calls it too. Double-freeing native
     * handles is not something to leave to chance.
     */
    fun close() {
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        remoteApplied = false
        pendingRemoteCandidates.clear()
        if (retained) {
            retained = false
            factoryHolder.release()
        }
    }

    // </editor-fold>

    // <editor-fold desc="Peer connection plumbing">

    private fun createPeerConnection(): PeerConnection? {
        if (peerConnection != null) return peerConnection
        val native = factoryHolder.factory()
        if (native == null) {
            failHard("The WebRTC engine is unavailable on this device.")
            return null
        }
        // Balanced in close(); the holder disposes the factory when the last
        // session lets go.
        factoryHolder.retain()
        retained = true
        val config = PeerConnection.RTCConfiguration(factoryHolder.iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            // No TURN. If a direct path cannot be found the call fails honestly.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = try {
            native.createPeerConnection(config, observer())
        } catch (e: Exception) {
            failHard("Could not start a connection: ${e.message ?: e::class.java.simpleName}")
            null
        } ?: run {
            failHard("Could not start a connection on this device.")
            null
        }
        peerConnection = pc
        return pc
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val compact = candidate.toPairingCandidate() ?: return
            if (pairing.payload == null) {
                // Not published yet: keep it for the payload being built.
                pairing.onLocalCandidate(compact)
            } else {
                pairing.onLocalCandidate(compact)
                onEvent.onLocalCandidate(this@P2PSession, compact)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> Unit // onConnected fires when the channel is actually open.
                PeerConnection.IceConnectionState.FAILED -> {
                    failHard(connectivityAdvice())
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    onEvent.onDisconnected(this@P2PSession, "The connection to this participant was lost.")
                }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) {
            // Only guests receive one, and only the host's channel is legitimate.
            if (channel.label() != CHANNEL_LABEL) {
                runCatching { channel.close() }
                return
            }
            dataChannel = channel.also(::wireDataChannel)
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onEvent.onConnected(this@P2PSession)
                PeerConnection.PeerConnectionState.FAILED -> failHard(connectivityAdvice())
                PeerConnection.PeerConnectionState.CLOSED ->
                    onEvent.onDisconnected(this@P2PSession, "The connection was closed.")
                else -> Unit
            }
        }
    }

    private fun wireDataChannel(channel: DataChannel) {
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    when (channel.state()) {
                        DataChannel.State.OPEN -> {
                            pairing.onConnected()
                            onEvent.onConnected(this@P2PSession)
                        }
                        DataChannel.State.CLOSED ->
                            onEvent.onDisconnected(this@P2PSession, "The connection was closed.")
                        else -> Unit
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val text = buffer.tryReadText() ?: return
                    onEvent.onMessage(this@P2PSession, text)
                }
            },
        )
    }

    /** Remote candidates that arrived before the remote description. */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    /** Applies a candidate from the peer. */
    fun addRemoteCandidate(compact: PairingPayload.IceCandidate) {
        val candidate = compact.toWebRtcCandidate()
        val pc = peerConnection ?: return
        if (!remoteApplied) {
            // Adding candidates before the description is an error in WebRTC,
            // and the ordering is not something the peer controls.
            pendingRemoteCandidates += candidate
            return
        }
        runCatching { pc.addIceCandidate(candidate) }
    }

    private fun drainPendingRemoteCandidates() {
        val pc = peerConnection ?: return
        pendingRemoteCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
        pendingRemoteCandidates.clear()
    }

    private fun failHard(reason: String) {
        pairing.fail(PairingSession.Reason.INVALID, reason)
        onEvent.onDisconnected(this, reason)
    }

    /**
     * What to actually tell the user.
     *
     * The single most common cause of a P2P failure is a network that forbids
     * direct connections, so say that plainly instead of showing an ICE state.
     */
    private fun connectivityAdvice(): String =
        "Could not connect directly to this participant. Mobile networks often block peer-to-peer " +
            "connections — try putting both devices on the same Wi-Fi network."

    // </editor-fold>

    private companion object {

        const val CHANNEL_LABEL = "hikaru-watchparty"

        /**
         * Constraints for a DataChannel-only offer.
         *
         * `DtlsSrtpKeyAgreement` is required by older stacks and harmless on new
         * ones; no media constraint is ever set, because there is no media.
         */
        fun dataChannelConstraints(): MediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
    }
}

// <editor-fold desc="Conversion helpers">

/**
 * Maps a WebRTC candidate onto the compact form that fits in a QR code.
 *
 * `org.webrtc.IceCandidate` exposes the raw `candidate:` line and nothing else
 * useful, so the fields are peeled off that line. An mDNS hostname
 * (`a1b2...local`) is accepted: it is what modern Android emits for privacy, and
 * the peer resolves it over the local network.
 */
internal fun IceCandidate.toPairingCandidate(): PairingPayload.IceCandidate? {
    val fields = candidateLineFields()
    if (fields.size < 8) return null
    val host = fields[4].takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
    val port = fields[5].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    val type = when (fields[7]) {
        "host" -> PairingPayload.IceCandidate.Type.HOST
        "srflx" -> PairingPayload.IceCandidate.Type.SRFLX
        "prflx" -> PairingPayload.IceCandidate.Type.PRFLX
        "relay" -> PairingPayload.IceCandidate.Type.RELAY
        else -> return null
    }
    return PairingPayload.IceCandidate(type, host, port)
}

/** Splits `candidate:<foundation> <component> <transport> <prio> <addr> <port> typ <type>`. */
private fun IceCandidate.candidateLineFields(): List<String> {
    val body = sdp.substringAfter("candidate:", missingDelimiterValue = "")
    if (body.isEmpty()) return emptyList()
    return body.trim().split(' ').filter { it.isNotEmpty() }
}

/**
 * Rebuilds a WebRTC candidate from the compact form.
 *
 * `sdpMid`/`sdpMLineIndex` target the single m-line, and the priority is
 * regenerated: the receiving stack recomputes it during the connectivity check.
 */
internal fun PairingPayload.IceCandidate.toWebRtcCandidate(): IceCandidate {
    val typeName = when (type) {
        PairingPayload.IceCandidate.Type.HOST -> "host"
        PairingPayload.IceCandidate.Type.SRFLX -> "srflx"
        PairingPayload.IceCandidate.Type.PRFLX -> "prflx"
        PairingPayload.IceCandidate.Type.RELAY -> "relay"
    }
    return IceCandidate(
        /* sdpMid = */ "0",
        /* sdpMLineIndex = */ 0,
        /* sdp = */ "candidate:1 1 udp 2130706431 $address $port typ $typeName",
    )
}

/** Decodes a DataChannel frame as UTF-8 text, or `null` when it is binary. */
private fun DataChannel.Buffer.tryReadText(): String? {
    if (binary) return null
    val bytes = ByteArray(data.remaining())
    data.get(bytes)
    return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
}

// </editor-fold>
