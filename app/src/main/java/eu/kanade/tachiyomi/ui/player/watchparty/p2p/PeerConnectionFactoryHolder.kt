package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.content.Context
import logcat.LogPriority
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the process-wide `PeerConnectionFactory` and the ICE server list.
 *
 * ## Why a single factory
 *
 * Building a `PeerConnectionFactory` starts native threads. Doing that per guest
 * would be wasteful, and leaking one per abandoned handshake eventually exhausts
 * native memory. One factory is created lazily on the first pairing and disposed
 * when the last session closes — a user who never opens Watch Party never
 * initialises WebRTC at all.
 *
 * The reference count is explicit rather than "dispose when a session ends"
 * because a host may run several sessions at once, one per guest.
 *
 * ## No TURN server
 *
 * The ICE list contains STUN servers and nothing else. STUN only tells a device
 * its own public address; it relays no traffic. A TURN server would relay the
 * connection, which would require Hikaru to operate (and pay for) a backend —
 * exactly what this feature is defined not to need. If STUN plus host candidates
 * cannot establish a direct path, the session fails and says so.
 */
class PeerConnectionFactoryHolder(
    private val context: Context,
    private val iceServerUrls: () -> List<String>,
) {

    private var factory: PeerConnectionFactory? = null
    private var activeSessions = 0
    private var nativeInitialised = false

    /** Last error message captured when factory initialisation failed. */
    @Volatile
    var lastError: String? = null
        private set

    /** True once any session has ever been opened; used by tests and diagnostics. */
    var everInitialised = false
        private set

    /**
     * The shared factory, created on first use.
     *
     * Returns `null` when this device cannot provide one. Callers must surface
     * that as a pairing failure: without a factory there is no P2P, and there is
     * deliberately no fallback transport to hide behind.
     */
    @Synchronized
    fun factory(): PeerConnectionFactory? {
        factory?.let { return it }
        return try {
            val appContext = context.applicationContext ?: context
            if (!nativeInitialised) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        // No audio device module and no video encoder factory:
                        // the room carries neither, so neither is initialised.
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                nativeInitialised = true
            }
            PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
                ?.also {
                    factory = it
                    everInitialised = true
                    lastError = null
                    logcat(LogPriority.INFO) { "WatchParty P2P: WebRTC initialised" }
                }
                ?: run {
                    lastError = "This device could not start the WebRTC engine."
                    null
                }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "WatchParty P2P: WebRTC initialisation failed" }
            lastError = when {
                e is UnsatisfiedLinkError || e.message.orEmpty().contains("jingle", ignoreCase = true) ->
                    "This build is missing the WebRTC native library for your device's CPU, so direct pairing is unavailable."
                !e.message.isNullOrBlank() ->
                    "Could not start the WebRTC engine: ${e.message}"
                else ->
                    "The WebRTC engine failed to start on this device (${e::class.java.simpleName})."
            }
            null
        }
    }

    /**
     * ICE servers from the current settings.
     *
     * Empty list means LAN-only mode: no STUN, host candidates only. That is a
     * supported configuration, not a broken one — same-Wi-Fi play works with it
     * and never leaks a public IP to a third party.
     */
    fun iceServers(): List<PeerConnection.IceServer> =
        iceServerUrls()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { url ->
                runCatching { PeerConnection.IceServer.builder(url).createIceServer() }
                    .getOrElse {
                        logcat(LogPriority.WARN) { "WatchParty P2P: ignoring invalid STUN URL" }
                        null
                    }
            }
            .filterNotNull()

    /** Marks a session as using the factory. */
    @Synchronized
    fun retain() {
        activeSessions++
    }

    /** Marks a session as finished; the last one out disposes the factory. */
    @Synchronized
    fun release() {
        if (activeSessions > 0) activeSessions--
        if (activeSessions == 0) dispose()
    }

    /** Live session count, for diagnostics. */
    @get:Synchronized
    val sessions: Int get() = activeSessions

    /**
     * Releases native resources.
     *
     * Called when the Watch Party UI is torn down. `PeerConnectionFactory.dispose`
     * must not run while a connection is still open, which is why this is guarded
     * by the session count instead of being called directly by the UI.
     */
    @Synchronized
    fun dispose() {
        factory?.dispose()
        factory = null
    }
}
