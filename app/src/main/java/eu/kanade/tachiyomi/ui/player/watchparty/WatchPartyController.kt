package eu.kanade.tachiyomi.ui.player.watchparty

import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.P2PWatchPartySession
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Player-facing wrapper around [WatchPartyRoomScope].
 *
 * Owns the mpv bridge, derives episode metadata (including AniList/MAL ids from
 * the tracker database) and forwards player callbacks to the process-wide anime
 * Watch Party room so rooms created from the anime page survive opening/closing
 * episodes and support serverless P2P pairing seamlessly.
 */
class WatchPartyController(
    private val viewModel: PlayerViewModel,
    private val prefs: WatchPartyPreferences,
) {
    private val getTracks: GetTracks = Injekt.get()
    private val roomScope: WatchPartyRoomScope = WatchPartyRoomScope.get()

    private var scope: CoroutineScope? = null
    private var lastPositionMs: Long = -1

    @Volatile private var resolvedMediaKey: Pair<String, String>? = null

    val state: StateFlow<WatchPartyUiState> get() = roomScope.state

    val pairing: StateFlow<P2PWatchPartySession.PairingSnapshot> get() = roomScope.pairing

    val effects: SharedFlow<WatchPartyEffect> get() = roomScope.effects

    val roomCode: String? get() = roomScope.roomCode

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
            // The player holds the legacy DB episode (SEpisode: `episode_number`);
            // convert once here so the rest of the feature sees the domain model.
            val episode = viewModel.currentEpisode.value?.toDomainEpisode() ?: return null
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

    private fun currentAnimeKey(): String {
        val anime = viewModel.currentAnime.value ?: return roomScope.boundAnime()
        val sourceId = viewModel.currentSource.value?.id ?: 0L
        return WatchPartyRoomScope.animeKey(sourceId = sourceId, animeUrl = anime.url)
    }

    /** Attaches this player's mpv bridge when a room was already opened on the anime page. */
    fun attachIfActive() {
        if (!roomScope.isActive()) return
        refreshMediaKey()
        roomScope.attachBridge(bridge)
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
        return roomScope.createRoom(currentAnimeKey(), bridge)
    }

    fun joinRoom(code: String): Boolean {
        refreshMediaKey()
        return roomScope.joinRoom(currentAnimeKey(), code, bridge)
    }

    fun leaveRoom() {
        roomScope.leave()
    }

    /** Detaches this player's bridge without destroying the anime-level room. */
    fun release() {
        roomScope.detachBridge(bridge)
        scope?.cancel()
        scope = null
    }

    fun isActive(): Boolean = roomScope.isActive()

    fun isServerless(): Boolean = roomScope.isServerless()

    /** The user preference; the feature is completely inert when this is false. */
    fun isEnabled(): Boolean = prefs.enabled().get()

    fun createInvitation(): Boolean = roomScope.createInvitation()

    fun acceptAnswer(raw: String): Boolean = roomScope.acceptAnswer(raw)

    fun beginPairing(raw: String): Boolean = roomScope.beginPairing(raw)

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

    fun onPlay() = roomScope.onPlay()

    fun onPause() = roomScope.onPause()

    fun onSeek(positionMs: Long) = roomScope.onSeek(positionMs)

    fun onPositionChanged(positionMs: Long) {
        if (positionMs == lastPositionMs) return
        lastPositionMs = positionMs
        roomScope.onPositionChanged(positionMs)
    }

    fun onEpisodeReady() {
        refreshMediaKey()
        roomScope.attachBridge(bridge)
    }

    fun onEpisodeChanged() {
        refreshMediaKey()
        roomScope.onEpisodeChanged(bridge.localEpisode())
    }

    // </editor-fold>

    fun requestReady() = roomScope.requestReady()

    fun requestNotReady() = roomScope.requestNotReady()

    fun requestCancelCountdown() = roomScope.requestCancelCountdown()

    fun requestSyncNow() = roomScope.requestSyncNow()

    fun sendChatMessage(text: String) = roomScope.sendChatMessage(text)

    fun sendReaction(emoji: String) = roomScope.sendReaction(emoji)

    fun localReady(): Boolean = roomScope.localReady()
}
