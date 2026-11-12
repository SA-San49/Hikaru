package eu.kanade.tachiyomi.ui.player.watchparty

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Player-facing wrapper around [WatchPartyEngine].
 *
 * Owns the mpv bridge, derives episode metadata (including AniList/MAL ids from
 * the tracker database) and forwards player callbacks.
 *
 * The state and effect flows always exist so Compose can observe them
 * unconditionally, but the engine, its socket and its ticker are only created
 * when the user actually opens or joins a room.
 */
class WatchPartyController(
    private val viewModel: PlayerViewModel,
    private val prefs: WatchPartyPreferences,
) {
    private val getTracks: GetTracks = Injekt.get()

    private var engine: WatchPartyEngine? = null
    private var scope: CoroutineScope? = null
    private var mirrorJob: Job? = null
    private var lastPositionMs: Long = -1

    @Volatile private var resolvedMediaKey: Pair<String, String>? = null

    private val _state = MutableStateFlow(WatchPartyUiState())
    val state: StateFlow<WatchPartyUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<WatchPartyEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<WatchPartyEffect> = _effects.asSharedFlow()

    /** Stable identity for the local participant (created on first access). */
    val selfId: String by lazy { prefs.participantId() }

    private val bridge = object : WatchPartyPlayerBridge {
        override fun positionMs(): Long = (viewModel.pos.value * 1000f).toLong().coerceAtLeast(0L)

        override fun durationMs(): Long? {
            val duration = viewModel.duration.value
            return if (duration > 0f) (duration * 1000f).toLong() else null
        }

        override fun isPaused(): Boolean = viewModel.paused.value

        override fun isBuffering(): Boolean = viewModel.pausedForCache.value || viewModel.isLoading.value

        override fun localEpisode(): EpisodeIdentity? {
            val episode = viewModel.currentEpisode.value ?: return null
            return EpisodeIdentity(
                animeTitle = viewModel.currentAnime.value?.title ?: "",
                episodeNumber = episode.episodeNumber,
                episodeTitle = episode.name,
                durationMs = durationMs(),
            )
        }

        override fun localMediaKey(): Pair<String, String>? = resolvedMediaKey

        override fun play() {
            MPVLib.setPropertyBoolean("pause", false)
        }

        override fun pause() {
            MPVLib.setPropertyBoolean("pause", true)
        }

        override fun seekTo(positionMs: Long) {
            val targetSeconds = (positionMs / 1000.0).coerceAtLeast(0.0)
            MPVLib.command(arrayOf("seek", targetSeconds.toString(), "absolute+exact"))
        }

        override fun setSpeed(speed: Double) {
            MPVLib.setPropertyDouble("speed", speed.coerceIn(0.5, 2.0))
        }
    }

    // <editor-fold desc="Lifecycle">

    private fun ensureScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }

    private fun ensureEngine(): WatchPartyEngine {
        engine?.let { return it }
        val created = WatchPartyEngine(
            player = bridge,
            prefs = prefs,
            transportFactory = { roomId, displayName ->
                RelayRoomTransport(
                    url = prefs.relayUrl().get(),
                    roomId = roomId,
                    participantId = prefs.participantId(),
                    displayName = displayName,
                    scope = ensureScope(),
                    client = RelayRoomTransport.defaultClient(),
                )
            },
        )
        engine = created
        val sessionScope = ensureScope()
        mirrorJob = sessionScope.launch {
            created.state.collect { _state.value = it }
        }
        sessionScope.launch {
            created.effects.collect { _effects.emit(it) }
        }
        return created
    }

    /**
     * Invite code captured by the deep link handler before the player composed.
     * Deliberately not acted on until the sheet opens: joining a room silently
     * from a link would start networking without the user seeing it.
     */
    var pendingInviteCode: String? = null
        private set

    fun rememberPendingInvite(code: String): Boolean {
        val normalized = RoomCode.normalize(code) ?: return false
        pendingInviteCode = normalized
        return true
    }

    /** Returns and clears the pending invite, for pre-filling the join field. */
    fun consumePendingInvite(): String? = pendingInviteCode?.also { pendingInviteCode = null }

    fun createRoom(): String {
        refreshMediaKey()
        return ensureEngine().createRoom()
    }

    fun joinRoom(code: String): Boolean {
        refreshMediaKey()
        return ensureEngine().joinRoom(code)
    }

    fun leaveRoom() {
        engine?.leaveRoom()
        _state.value = WatchPartyUiState()
    }

    /** Releases everything. Safe to call when no room was ever created. */
    fun release() {
        mirrorJob?.cancel()
        mirrorJob = null
        engine?.leaveRoom()
        engine = null
        scope?.cancel()
        scope = null
        _state.value = WatchPartyUiState()
    }

    fun isActive(): Boolean = _state.value.active

    /** The user preference; the feature is completely inert when this is false. */
    fun isEnabled(): Boolean = prefs.enabled().get()

    // </editor-fold>

    /** Resolves the AniList/MAL id once per anime, in the background. */
    fun refreshMediaKey() {
        val anime = viewModel.currentAnime.value ?: return
        ensureScope().launch(Dispatchers.IO) {
            val key = runCatching {
                val tracks = getTracks.await(anime.id)
                val anilist = tracks.firstOrNull { it.trackerId == TrackerManager.ANILIST }
                val mal = tracks.firstOrNull { it.trackerId == 1L }
                when {
                    anilist != null -> "anilist" to anilist.remoteId.toString()
                    mal != null -> "mal" to mal.remoteId.toString()
                    else -> null
                }
            }.getOrNull()
            resolvedMediaKey = key
            logcat(LogPriority.VERBOSE) { "WatchParty media key: $key" }
        }
    }

    // <editor-fold desc="Player callbacks">

    fun onPlay() = engine?.onLocalPlay()

    fun onPause() = engine?.onLocalPause()

    fun onSeek(positionMs: Long) = engine?.onLocalSeek(positionMs)

    fun onPositionChanged(positionMs: Long) {
        if (positionMs == lastPositionMs) return
        lastPositionMs = positionMs
        engine?.onLocalPositionChanged(positionMs)
    }

    fun onEpisodeReady() {
        refreshMediaKey()
        engine?.onEpisodeReady(bridge.localEpisode())
    }

    fun onEpisodeChanged() {
        refreshMediaKey()
        engine?.onLocalEpisodeChanged(bridge.localEpisode())
    }

    // </editor-fold>

    fun requestReady() = engine?.requestReady()

    fun requestNotReady() = engine?.requestNotReady()

    fun requestCancelCountdown() = engine?.requestCancelCountdown()

    fun requestSyncNow() = engine?.requestSyncNow()

    fun sendChatMessage(text: String) = engine?.sendChatMessage(text)

    fun sendReaction(emoji: String) = engine?.sendReaction(emoji)

    fun localReady(): Boolean = engine?.isLocalReady() == true
}
