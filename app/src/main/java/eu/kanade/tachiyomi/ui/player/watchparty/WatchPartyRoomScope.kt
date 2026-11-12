package eu.kanade.tachiyomi.ui.player.watchparty

import android.content.Context
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.P2PWatchPartySession
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Process-wide Watch Party room, independent of any screen.
 *
 * ## Why this is not part of the player
 *
 * Watch Party is an **anime-level** feature: the action lives on the anime detail
 * page and the room belongs to the *series*, not to whichever episode someone has
 * open. If the room were owned by the player controllers, then:
 *
 *  - opening an episode would create the room, and closing it would destroy the
 *    room — so "next episode" would silently kick everyone out;
 *  - a host could not create a room without first starting an episode, which is
 *    backwards: you invite people *before* pressing play;
 *  - the guest list, chat history and ready states would be tied to a screen
 *    instead of to the people in the room.
 *
 * So the room lives here, in a singleton, for as long as the user keeps it. Both
 * the anime screen and the player observe the same instance, which is what makes
 * the room survive navigating between episodes.
 *
 * ## Lifetime and cost
 *
 * Nothing is created until [createRoom] or [joinRoom] is called, and [leave]
 * releases everything. A user who never uses the feature pays nothing: no
 * coroutine scope, no WebRTC factory, no socket.
 */
class WatchPartyRoomScope(
    private val prefs: WatchPartyPreferences,
    private val appContext: Context,
) {

    private var scope: CoroutineScope? = null
    private var engine: WatchPartyEngine? = null
    private var serverless: P2PWatchPartySession? = null
    private var serverlessUsesPairing = false
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(WatchPartyUiState())
    val state: StateFlow<WatchPartyUiState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow(P2PWatchPartySession.PairingSnapshot())
    val pairing: StateFlow<P2PWatchPartySession.PairingSnapshot> = _pairing.asStateFlow()

    /**
     * The anime this room belongs to.
     *
     * Rooms are anime-level: `<sourceId>:<animeUrlHash>`, so every episode of the
     * same series maps to one room and switching episodes changes nothing.
     */
    private var animeKey: String = ""

    /** Identity of the anime the current room is bound to, or blank when idle. */
    fun boundAnime(): String = animeKey

    /**
     * The code guests type to find this room.
     *
     * On a host this is generated when the room is created. On a guest it is the
     * code that was entered. Blank while idle.
     */
    var roomCode: String? = null
        private set

    fun isActive(): Boolean = _state.value.active

    fun isHost(): Boolean = _state.value.isHost

    fun isEnabled(): Boolean = prefs.enabled().get()

    fun isServerless(): Boolean = serverlessUsesPairing

    private fun ensureScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }

    // <editor-fold desc="Room lifecycle">

    /**
     * Creates a room for [animeKey].
     *
     * The bridge is supplied by the caller because position and pause state only
     * exist once a player is attached; before then the room reports a paused
     * position of zero, which is the truth.
     */
    fun createRoom(animeKey: String, bridge: WatchPartyPlayerBridge): String {
        this.animeKey = animeKey
        prepare(animeKey, isHost = true, bridge = bridge)
        val code = engine().createRoom()
        roomCode = code
        serverless?.bindRoom(code)
        startTicker()
        return code
    }

    /**
     * Joins a room.
     *
     * Two different things can arrive here, and they are not interchangeable:
     *
     *  - a **room code** in relay mode, which the engine validates and dials;
     *  - a **scanned invitation** in serverless mode, which carries its own room
     *    identity and needs no code at all.
     *
     * In serverless mode the room id comes from the invitation, so the engine is
     * started with that id instead of demanding a code the guest never had.
     */
    fun joinRoom(animeKey: String, code: String, bridge: WatchPartyPlayerBridge): Boolean {
        this.animeKey = animeKey
        val serverlessMode = P2PWatchPartySession.usesServerlessPairing(prefs)

        // In serverless mode `code` is a scanned/pasted invitation payload.
        val invitation = if (serverlessMode) PairingPayload.decode(code) else null
        if (serverlessMode && invitation == null) {
            _state.value = WatchPartyUiState(
                error = "That is not a Hikaru Watch Party invitation.",
            )
            return false
        }

        val normalized = RoomCode.normalize(code) ?: if (serverlessMode) null else run {
            _state.value = WatchPartyUiState(error = "That room code is not valid.")
            return false
        }

        val roomId = invitation?.roomId ?: normalized!!
        prepare(animeKey, isHost = false, bridge = bridge, roomIdHint = roomId)

        // The engine still owns room lifecycle and presence; in serverless mode
        // there is no code to validate, so its normal join path is bypassed and
        // the session is started directly against the invitation's room.
        val joined = if (serverlessMode) {
            engine().startAsGuest(roomId)
        } else {
            engine().joinRoom(roomId)
        }
        if (!joined) {
            stop()
            return false
        }

        if (serverlessMode) {
            if (!beginPairing(invitation!!)) {
                // Pairing failed; the session already holds the reason.
                stop()
                return false
            }
        } else {
            roomCode = roomId
        }
        serverless?.bindRoom(roomId)
        startTicker()
        return true
    }

    /**
     * Swaps in the bridge from the player that just opened.
     *
     * Called when the user moves between episodes. The room, its participants and
     * its chat are untouched: only the source of local playback state changes.
     * This is what makes the room survive an episode change.
     */
    fun attachBridge(bridge: WatchPartyPlayerBridge) {
        val current = engine ?: return
        current.attachPlayer(bridge)
        // Tell the room what is now loaded, so guests get an EXACT/PROBABLE/
        // MISMATCH verdict for the new episode rather than a stale one.
        current.onEpisodeReady(bridge.localEpisode())
    }

    fun leave() {
        stop()
        _state.value = WatchPartyUiState()
        animeKey = ""
    }

    private fun prepare(
        animeKey: String,
        isHost: Boolean,
        bridge: WatchPartyPlayerBridge,
        roomIdHint: String? = null,
    ) {
        stop()
        val created = WatchPartyEngine(
            player = bridge,
            prefs = prefs,
            transportFactory = { roomId, _ ->
                if (serverlessUsesPairing) {
                    // The engine's room id is the one invitations are bound to.
                    serverless?.transport() ?: OfflineRoomTransport()
                } else {
                    RelayRoomTransport(
                        url = prefs.relayUrl().get(),
                        roomId = animeKey,
                        participantId = prefs.participantId(),
                        displayName = { prefs.effectiveDisplayName() },
                        scope = ensureScope(),
                        client = RelayRoomTransport.defaultClient(),
                    )
                }
            },
        )
        engine = created

        serverlessUsesPairing = P2PWatchPartySession.usesServerlessPairing(prefs)
        if (serverlessUsesPairing) {
            val session = P2PWatchPartySession(
                context = appContext,
                prefs = prefs,
                roomId = roomIdHint.orEmpty(),
                isHost = isHost,
            ).also { p2p ->
                p2p.onLocalFrame = { text -> created.onMessage(text) }
            }
            serverless = session
            ensureScope().launch { session.pairing.collect { _pairing.value = it } }
        }

        val sessionScope = ensureScope()
        sessionScope.launch { created.state.collect { _state.value = it } }
    }

    private fun engine(): WatchPartyEngine = engine ?: error("room not prepared")

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = ensureScope().launch {
            while (isActive) {
                serverless?.tick()
                delay(1_000L)
            }
        }
    }

    private fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        serverless?.close()
        serverless = null
        serverlessUsesPairing = false
        engine?.leaveRoom()
        engine = null
        scope?.cancel()
        scope = null
        roomCode = null
        _pairing.value = P2PWatchPartySession.PairingSnapshot()
    }

    // </editor-fold>

    // <editor-fold desc="Serverless pairing">

    fun createInvitation(): Boolean = serverless?.createInvitation() == true

    fun acceptAnswer(raw: String): Boolean = serverless?.acceptAnswer(raw) == true

    fun beginPairing(raw: String): Boolean = serverless?.beginPairing(raw) == true

    // </editor-fold>

    // <editor-fold desc="Room actions, forwarded to the engine">

    fun requestReady() = engine?.requestReady()

    fun requestNotReady() = engine?.requestNotReady()

    fun requestCancelCountdown() = engine?.requestCancelCountdown()

    fun requestSyncNow() = engine?.requestSyncNow()

    fun sendChatMessage(text: String) = engine?.sendChatMessage(text)

    fun sendReaction(emoji: String) = engine?.sendReaction(emoji)

    fun localReady(): Boolean = engine?.isLocalReady() == true

    fun onPlay() = engine?.onLocalPlay()

    fun onPause() = engine?.onLocalPause()

    fun onSeek(positionMs: Long) = engine?.onLocalSeek(positionMs)

    fun onPositionChanged(positionMs: Long) = engine?.onLocalPositionChanged(positionMs)

    fun onEpisodeChanged(episode: EpisodeIdentity?) = engine?.onLocalEpisodeChanged(episode)

    // </editor-fold>

    /**
     * A transport with nowhere to go, used only in the brief window between the
     * engine starting and the serverless session's transport existing.
     */
    private class OfflineRoomTransport : RoomTransport {
        override var listener: RoomTransport.Listener? = null

        override fun connect() {
            listener?.onStateChanged(RoomTransport.State.Disconnected)
        }

        override fun send(text: String) = Unit

        override fun close() = Unit
    }

    companion object {
        /**
         * Builds the anime-level room key.
         *
         * Stable across episodes **and** across app restarts, which is what lets a
         * guest rejoin the same room after closing the player. The URL is hashed
         * rather than embedded so the key cannot leak a source/genre URL into a QR
         * payload shown to other people.
         */
        fun animeKey(sourceId: Long, animeUrl: String): String {
            val hash = animeUrl.hashCode().toUInt().toString(16)
            return "$sourceId:$hash"
        }

        /**
         * Process-wide instance.
         *
         * Held here rather than in Injekt because it needs an application context
         * and a preference store, both of which Injekt can supply, but keeping the
         * factory next to the class makes the dependency obvious.
         */
        private var instance: WatchPartyRoomScope? = null

        fun get(): WatchPartyRoomScope = instance ?: WatchPartyRoomScope(
            prefs = Injekt.get(),
            appContext = Injekt.get<android.app.Application>(),
        ).also { instance = it }

        /** Drops the singleton, releasing anything it still holds. */
        fun releaseInstance() {
            instance?.leave()
            instance = null
        }
    }
}

/**
 * The bridge used before any episode is open.
 *
 * Watch Party rooms are created from the anime page, where no player exists yet.
 * Rather than fabricate a fake position or invent a pause state, this reports
 * exactly that: nothing is loaded, nothing is playing, position zero. The engine
 * treats that as an unresolved episode, which is what a guest sees until the host
 * actually starts an episode.
 *
 * When the user does open one, the player's real bridge replaces this through
 * [WatchPartyRoomScope.attachBridge].
 */
object IdlePlayerBridge : WatchPartyPlayerBridge {
    override fun positionMs(): Long = 0L

    override fun durationMs(): Long? = null

    override fun isPaused(): Boolean = true

    override fun isBuffering(): Boolean = false

    override fun localEpisode(): EpisodeIdentity? = null

    override fun localMediaKey(): Pair<String, String>? = null

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setSpeed(speed: Double) = Unit
}
