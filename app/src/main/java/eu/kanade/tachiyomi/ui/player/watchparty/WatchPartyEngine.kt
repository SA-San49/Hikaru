package eu.kanade.tachiyomi.ui.player.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

/**
 * Everything the player has to provide to the Watch Party layer.
 *
 * The engine never touches mpv directly: it asks for a snapshot and issues
 * commands. That keeps the synchronisation policy testable and, together with
 * [WatchPartyEngine.isApplyingRemote], prevents feedback loops where applying a
 * remote command is picked up again as a *local* event and re-broadcast.
 */
interface WatchPartyPlayerBridge {
    fun positionMs(): Long
    fun durationMs(): Long?
    fun isPaused(): Boolean
    fun isBuffering(): Boolean

    /** Metadata of the episode currently open on this device. */
    fun localEpisode(): EpisodeIdentity?

    /** Provider-independent tracking id: `"anilist" to "12345"`. */
    fun localMediaKey(): Pair<String, String>?

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Sets the mpv playback rate; `1.0` restores normal speed. */
    fun setSpeed(speed: Double)
}

/** Snapshot of the room, surfaced to Compose. */
data class WatchPartyUiState(
    val active: Boolean = false,
    val roomId: String = "",
    val isHost: Boolean = false,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val participants: List<ParticipantInfo> = emptyList(),
    val readyStates: Map<String, ReadyState> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    val reactions: List<ReactionEvent> = emptyList(),
    val countdown: ReadyCheckController.Countdown? = null,
    val syncPhase: SyncNowController.Phase = SyncNowController.Phase.IDLE,
    val syncPendingIds: List<String> = emptyList(),
    val episode: EpisodeIdentity? = null,
    val episodeMatch: EpisodeMatch? = null,
    val warning: String? = null,
    val error: String? = null,
    val hostId: String? = null,
) {
    val isCountingDown: Boolean get() = countdown != null
    val isSyncing: Boolean get() = syncPhase == SyncNowController.Phase.PREPARING
    val connected: Boolean get() = connection == ConnectionState.CONNECTED

    fun participant(id: String): ParticipantInfo? = participants.firstOrNull { it.participantId == id }

    fun nameOf(id: String): String = participant(id)?.displayName ?: "Guest"
}

/** Discrete effects the engine asks the UI layer to perform. */
sealed interface WatchPartyEffect {
    data class ShowMessage(val message: String) : WatchPartyEffect
    data object PlaybackStarted : WatchPartyEffect
}

/**
 * Room engine: membership, playback authority, ready check, Sync Now, chat and
 * reactions over a single transport.
 *
 * The engine is inert until [createRoom] or [joinRoom] is called and [leaveRoom]
 * releases every resource, so an idle player never opens a socket.
 */
class WatchPartyEngine(
    player: WatchPartyPlayerBridge,
    private val prefs: WatchPartyPreferences,
    private val transportFactory: (roomId: String, displayName: () -> String) -> RoomTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RoomTransport.Listener {

    /**
     * The player currently attached to this room.
     *
     * Swappable because the room is anime-level and outlives the player: when the
     * user moves to the next episode a new player (with a new mpv instance) takes
     * over, while the room, its participants and its chat stay exactly as they
     * were. Every read goes through this field, never a captured reference.
     */
    @Volatile
    private var player: WatchPartyPlayerBridge = player

    /**
     * Swaps the attached player without disturbing the room.
     *
     * Called when the user opens a different episode. The room keeps running: no
     * socket is re-dialled, no participant list is rebuilt, no chat is lost.
     */
    fun attachPlayer(newPlayer: WatchPartyPlayerBridge) {
        player = newPlayer
    }

    val selfId: String = prefs.participantId()
    val selfName: () -> String = { prefs.effectiveDisplayName() }

    private val router = EventRouter { roomId }
    private val syncEngine = PlaybackSyncEngine()
    private val readyCheck = ReadyCheckController()
    private val syncNow = SyncNowController()
    private val reactions = ReactionAggregator()

    private var roomId: String = ""
    private var transport: RoomTransport? = null
    private var sessionScope: CoroutineScope? = null
    private var tickerJob: Job? = null
    private var speedResetJob: Job? = null
    private var sequence: Long = 0L

    /** True while a remote command is being applied; local callbacks must not echo it. */
    var isApplyingRemote: Boolean = false
        private set

    private val _state = MutableStateFlow(WatchPartyUiState())
    val state: StateFlow<WatchPartyUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<WatchPartyEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<WatchPartyEffect> = _effects.asSharedFlow()

    private val participants = LinkedHashMap<String, ParticipantInfo>()
    private val manualReady = HashMap<String, Boolean>()
    private val seenReactionIds = HashSet<String>()

    private var lastReactionQueuedAt = 0L
    private var lastPositionSentAt = 0L
    private var lastPresenceSentAt = 0L
    private var countdownSuppressedUntil = 0L

    /** Last authoritative snapshot received from the host (guests only). */
    private var authoritativeSnapshot: PlaybackSnapshot? = null

    private fun now(): Long = clock()

    // <editor-fold desc="Room lifecycle">

    fun createRoom(): String {
        val code = RoomCode.generate()
        startSession(code, isHost = true)
        return code
    }

    /**
     * Starts a guest session against a room id that arrived out-of-band.
     *
     * Serverless pairing takes its room identity from the scanned invitation, so
     * there is no code to normalise or validate — the invitation already proved
     * which room it belongs to. Everything else is the ordinary guest path.
     */
    fun startAsGuest(roomId: String): Boolean {
        if (roomId.isBlank()) {
            _state.update { it.copy(error = "That invitation has no room.") }
            return false
        }
        startSession(roomId, isHost = false)
        return true
    }

    fun joinRoom(rawCode: String): Boolean {
        val code = RoomCode.normalize(rawCode)
        if (code == null) {
            _state.update { it.copy(error = "That room code is not valid.") }
            return false
        }
        startSession(code, isHost = false)
        return true
    }

    private fun startSession(newRoomId: String, isHost: Boolean) {
        teardownSession()
        router.reset()
        syncEngine.reset()
        readyCheck.reset()
        syncNow.reset()
        reactions.drain()
        participants.clear()
        manualReady.clear()
        seenReactionIds.clear()
        sequence = 0
        lastPositionSentAt = 0
        lastPresenceSentAt = 0
        countdownSuppressedUntil = 0
        authoritativeSnapshot = null
        roomId = newRoomId

        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transport = transportFactory(newRoomId) { selfName() }.also { it.listener = this }

        participants[selfId] = ParticipantInfo(
            participantId = selfId,
            displayName = selfName(),
            role = if (isHost) ParticipantRole.HOST else ParticipantRole.GUEST,
            paused = player.isPaused(),
            positionMs = player.positionMs(),
            updatedAt = now(),
        )

        _state.update {
            it.copy(
                active = true,
                roomId = newRoomId,
                isHost = isHost,
                hostId = if (isHost) selfId else null,
                connection = ConnectionState.CONNECTING,
                error = null,
                warning = null,
                participants = participants.values.toList(),
                readyStates = emptyMap(),
                chat = emptyList(),
                reactions = emptyList(),
                countdown = null,
                syncPhase = SyncNowController.Phase.IDLE,
                syncPendingIds = emptyList(),
                episode = player.localEpisode(),
                episodeMatch = null,
            )
        }

        transport?.connect()
        startTicker()
    }

    fun leaveRoom() {
        if (_state.value.active) {
            sendEvent(WatchPartyEventType.PARTICIPANT_LEAVE) {
                it.copy(participantId = selfId, displayName = selfName())
            }
        }
        teardownSession()
        roomId = ""
        participants.clear()
        manualReady.clear()
        seenReactionIds.clear()
        _state.value = WatchPartyUiState()
    }

    private fun teardownSession() {
        tickerJob?.cancel()
        tickerJob = null
        speedResetJob?.cancel()
        speedResetJob = null
        transport?.listener = null
        transport?.close()
        transport = null
        sessionScope?.cancel()
        sessionScope = null
        if (isApplyingRemote) {
            isApplyingRemote = false
            player.setSpeed(1.0)
        }
    }

    /**
     * Swaps the live transport, keeping the room and its state.
     *
     * Serverless pairing needs this: the engine is started when the room is
     * created, but a guest's data channel does not exist until the QR exchange
     * completes. Rather than delay the engine (which would leave the host with no
     * room at all), the transport is attached the moment it becomes real.
     *
     * Any frames already queued on the old transport are dropped: a room that
     * changes transport has not been able to send anyway.
     */
    fun attachTransport(newTransport: RoomTransport) {
        if (!_state.value.active) return
        transport?.listener = null
        transport?.close()
        transport = newTransport
        newTransport.listener = this
        newTransport.connect()
    }

    // </editor-fold>

    // <editor-fold desc="Transport callbacks">

    override fun onStateChanged(state: RoomTransport.State) {
        if (!_state.value.active) return
        val connection = when (state) {
            RoomTransport.State.Disconnected -> ConnectionState.DISCONNECTED
            RoomTransport.State.Connecting -> ConnectionState.RECONNECTING
            is RoomTransport.State.Connected -> ConnectionState.CONNECTED
            is RoomTransport.State.Failed -> ConnectionState.DISCONNECTED
        }
        _state.update { it.copy(connection = connection) }

        if (connection == ConnectionState.CONNECTED) {
            // Re-announce after every (re)connect: the relay keeps no room state,
            // so presence is rebuilt entirely from these announcements.
            announceJoin()
            if (_state.value.isHost) broadcastPlayback(force = true)
        }
    }

    override fun onMessage(text: String) {
        val event = RoomMessageCodec.decode(text) ?: return
        when (val routed = router.route(event)) {
            is EventRouter.Result.Dropped -> logcat(LogPriority.VERBOSE) { "WatchParty drop: ${routed.reason}" }
            is EventRouter.Result.Deliver -> {
                try {
                    handleEvent(routed.event)
                } catch (e: Exception) {
                    // A malformed or hostile event must never take the player down.
                    logcat(LogPriority.ERROR, e) { "WatchParty: failed to handle ${routed.event.type}" }
                }
            }
        }
    }

    // </editor-fold>

    // <editor-fold desc="Outgoing events">

    private fun nextSeq(): Long = ++sequence

    private fun sendEvent(type: WatchPartyEventType, payload: EventPayload = EventPayload()) {
        if (!_state.value.active) return
        val transport = transport ?: return
        val event = RoomEvent(
            type = type,
            roomId = roomId,
            senderId = selfId,
            senderName = selfName(),
            seq = nextSeq(),
            sentAtMs = now(),
            payload = payload,
        )
        transport.send(RoomMessageCodec.encode(event))
    }

    private inline fun sendEvent(type: WatchPartyEventType, block: (EventPayload) -> EventPayload) {
        sendEvent(type, block(EventPayload()))
    }

    private fun announceJoin() {
        val role = if (_state.value.isHost) ParticipantRole.HOST else ParticipantRole.GUEST
        participants[selfId] = (participants[selfId] ?: ParticipantInfo(selfId, selfName())).copy(
            role = role,
            paused = player.isPaused(),
            positionMs = player.positionMs(),
            updatedAt = now(),
        )
        publishParticipants()
        lastPresenceSentAt = now()
        sendEvent(WatchPartyEventType.PARTICIPANT_JOIN) {
            it.copy(
                participantId = selfId,
                displayName = selfName(),
                role = role,
                paused = player.isPaused(),
                positionMs = player.positionMs(),
            )
        }
        if (_state.value.isHost) {
            sendEvent(WatchPartyEventType.EPISODE_CHANGE) { it.copy(episode = player.localEpisode()) }
        }
    }

    // </editor-fold>

    // <editor-fold desc="Local player callbacks">

    fun onLocalPlay() {
        if (!isLive() || isApplyingRemote) return
        if (_state.value.isHost) sendPlayback(WatchPartyEventType.PLAY, paused = false)
        sendPresence()
    }

    fun onLocalPause() {
        if (!isLive() || isApplyingRemote) return
        if (_state.value.isHost) sendPlayback(WatchPartyEventType.PAUSE, paused = true)
        sendPresence()
    }

    fun onLocalSeek(positionMs: Long) {
        if (!isLive() || isApplyingRemote) return
        if (_state.value.isHost) {
            sendPlayback(WatchPartyEventType.SEEK, paused = player.isPaused(), positionMs = positionMs)
        }
        sendPresence()
    }

    /**
     * Called from the mpv `time-pos` tick. The host broadcasts an adaptive
     * heartbeat (never a packet per frame); guests converge on the last
     * authoritative snapshot.
     */
    fun onLocalPositionChanged(positionMs: Long) {
        if (!isLive() || isApplyingRemote) return
        val nowMs = now()
        if (_state.value.isHost) {
            if (nowMs - lastPositionSentAt >= HOST_POSITION_INTERVAL_MS) {
                lastPositionSentAt = nowMs
                sendEvent(WatchPartyEventType.POSITION) {
                    it.copy(
                        positionMs = positionMs,
                        paused = player.isPaused(),
                        durationMs = player.durationMs(),
                    )
                }
            }
        } else {
            followAuthoritative(localPositionMs = positionMs)
        }
    }

    /** Guest drift correction towards the host's projected position. */
    private fun followAuthoritative(localPositionMs: Long) {
        val snapshot = authoritativeSnapshot ?: return
        if (_state.value.isSyncing || _state.value.isCountingDown) return
        val expected = syncEngine.projectPosition(snapshot, now())
        val decision = syncEngine.evaluate(
            expected = expected,
            actual = localPositionMs,
            paused = player.isPaused(),
            buffering = player.isBuffering(),
            nowMs = now(),
            duration = player.durationMs(),
        )
        applyDecision(decision)
    }

    /** Called when the local episode finishes loading (or changes). */
    fun onEpisodeReady(episode: EpisodeIdentity?) {
        if (!isLive()) return
        val local = episode ?: player.localEpisode()
        val roomEpisode = _state.value.episode
        val (key, source) = player.localMediaKey() ?: (null to null)
        val match = if (roomEpisode == null) null else EpisodeMatcher.match(roomEpisode, local, key, source)

        _state.update {
            it.copy(
                episode = roomEpisode ?: local,
                episodeMatch = match,
                warning = when (match) {
                    null, EpisodeMatch.EXACT -> null
                    EpisodeMatch.PROBABLE -> "Matched by title only — please check the episode."
                    EpisodeMatch.UNRESOLVED -> "Open ${roomEpisode?.displayName ?: "the episode"} to join in sync."
                    EpisodeMatch.MISMATCH -> "You are on a different episode — sync paused until you switch."
                },
            )
        }

        if (_state.value.isHost) {
            sendEvent(WatchPartyEventType.EPISODE_CHANGE) { it.copy(episode = local) }
            broadcastPlayback(force = true)
        }
    }

    fun onLocalEpisodeChanged(episode: EpisodeIdentity?) {
        if (!isLive()) return
        // A new episode invalidates readiness: everyone has to prepare again.
        manualReady.clear()
        _state.update {
            it.copy(episode = episode ?: it.episode, episodeMatch = null, warning = null, countdown = null)
        }
        readyCheck.reset()
        authoritativeSnapshot = null
        if (_state.value.isHost) {
            sendEvent(WatchPartyEventType.EPISODE_CHANGE) { it.copy(episode = episode) }
        }
    }

    // </editor-fold>

    // <editor-fold desc="Ready check">

    fun requestReady() {
        if (!isLive()) return
        manualReady[selfId] = true
        countdownSuppressedUntil = 0
        sendEvent(WatchPartyEventType.READY) { it.copy(participantId = selfId, displayName = selfName()) }
        publishReadyStates()
        maybeStartCountdown()
    }

    fun requestNotReady() {
        if (!isLive()) return
        manualReady[selfId] = false
        sendEvent(WatchPartyEventType.NOT_READY) { it.copy(participantId = selfId, displayName = selfName()) }
        publishReadyStates()
        if (readyCheck.isCountingDown) cancelCountdown(reason = "You are no longer ready")
    }

    fun isLocalReady(): Boolean = manualReady[selfId] == true

    private fun cancelCountdown(reason: String) {
        if (!readyCheck.isCountingDown) return
        readyCheck.cancel(now())
        publishCountdown()
        sendEvent(WatchPartyEventType.COUNTDOWN_CANCEL) { it.copy(reason = reason) }
        // Do not immediately restart: wait for an explicit ready toggle.
        countdownSuppressedUntil = now() + COUNTDOWN_RESTART_GUARD_MS
        _effects.tryEmit(WatchPartyEffect.ShowMessage("Countdown cancelled"))
    }

    /** Any participant may cancel a countdown. */
    fun requestCancelCountdown() {
        if (!isLive() || !readyCheck.isCountingDown) return
        cancelCountdown(reason = "${selfName()} cancelled the countdown")
    }

    private fun maybeStartCountdown() {
        if (!isLive() || !_state.value.isHost) return
        if (readyCheck.isCountingDown || syncNow.isActive) return
        if (now() < countdownSuppressedUntil) return
        if (participants.size < 2) return

        val states = buildReadyStates(participants.keys.toList())
        if (!allPrepared(states)) return

        val countdownId = UUID.randomUUID().toString()
        val startedAt = now()
        val deadline = startedAt + READY_COUNTDOWN_SECONDS * 1000L
        if (readyCheck.start(countdownId, deadline, startedAt) is ReadyCheckController.Transition.Started) {
            publishCountdown()
            sendEvent(WatchPartyEventType.COUNTDOWN_START) {
                it.copy(countdownId = countdownId, deadlineMs = deadline)
            }
        }
    }

    private fun publishCountdown() {
        _state.update { it.copy(countdown = readyCheck.countdown) }
    }

    // </editor-fold>

    // <editor-fold desc="Sync Now">

    fun requestSyncNow() {
        if (!isLive() || isApplyingRemote) return
        if (_state.value.isHost) {
            beginSync(requestedBy = selfName())
            return
        }
        // Guests ask the coordinator; they never move everyone themselves.
        sendEvent(WatchPartyEventType.SYNC_REQUEST) {
            it.copy(participantId = selfId, positionMs = player.positionMs(), displayName = selfName())
        }
        _effects.tryEmit(WatchPartyEffect.ShowMessage("Sync requested"))
    }

    /** Host-side entry point. Concurrent requests merge into one operation. */
    private fun beginSync(requestedBy: String) {
        if (!_state.value.isHost || syncNow.isActive) return
        if (readyCheck.isCountingDown) cancelCountdown(reason = "Sync in progress")

        val op = syncNow.request(
            requestedBy = requestedBy,
            targetPositionMs = player.positionMs(),
            nowMs = now(),
            operationId = UUID.randomUUID().toString(),
            durationMs = player.durationMs(),
        ) ?: return

        publishSyncState()
        sendEvent(WatchPartyEventType.SYNC_BEGIN) {
            it.copy(
                syncId = op.id,
                targetPositionMs = op.targetPositionMs,
                targetSentAtMs = op.targetSentAtMs,
                reason = "requested by $requestedBy",
            )
        }

        // The host freezes and prepares itself exactly like the guests do.
        applyRemote {
            player.pause()
            player.setSpeed(1.0)
            player.seekTo(op.targetPositionMs)
        }
        syncNow.markPrepared(selfId, op.id)
        publishSyncState()

        sessionScope?.launch {
            delay(SYNC_HOST_GATHER_MS)
            while (isActive && syncNow.isActive) {
                if (syncNow.shouldResume(participants.keys.toList(), now(), hostPrepared = true)) {
                    completeSync()
                    return@launch
                }
                if (syncNow.timedOut(now())) {
                    failSync("Sync timed out")
                    return@launch
                }
                delay(SYNC_POLL_MS)
            }
        }
    }

    private fun maybeCompleteSync() {
        if (!_state.value.isHost || !syncNow.isActive) return
        if (syncNow.shouldResume(participants.keys.toList(), now(), hostPrepared = true)) {
            completeSync()
        }
    }

    private fun completeSync() {
        val op = syncNow.finish(now()) ?: return
        publishSyncState()
        val target = op.targetPositionMs
        // Coordinated restart at the host's validated position.
        applyRemote {
            player.setSpeed(1.0)
            val clamped = syncEngine.clampRemoteSeek(target, player.durationMs()) ?: target
            player.seekTo(clamped)
            player.play()
        }
        syncEngine.reset()
        authoritativeSnapshot = PlaybackSnapshot(
            positionMs = target,
            paused = false,
            durationMs = player.durationMs(),
            sentAtMs = now(),
        )
        sendEvent(WatchPartyEventType.SYNC_RESUME) { it.copy(syncId = op.id, targetPositionMs = target) }
        _effects.tryEmit(WatchPartyEffect.ShowMessage("Synced — resuming together"))
    }

    private fun failSync(reason: String) {
        val aborted = syncNow.abort(reason) ?: return
        publishSyncState()
        logcat(LogPriority.WARN) { "WatchParty: sync $aborted aborted: $reason" }
        _effects.tryEmit(WatchPartyEffect.ShowMessage(reason))
    }

    private fun publishSyncState() {
        val operation = syncNow.operation
        _state.update {
            it.copy(
                syncPhase = syncNow.phase,
                syncPendingIds = operation?.let { op ->
                    participants.keys.filter { id -> id !in op.attempted }
                } ?: emptyList(),
            )
        }
    }

    private fun onSyncBegin(event: RoomEvent) {
        val payload = event.payload
        val syncId = payload.syncId ?: return
        val target = payload.targetPositionMs ?: return
        if (syncNow.operation?.id == syncId) return

        // A newer operation replaces a stale one instead of being ignored.
        syncNow.reset()
        val op = syncNow.request(
            requestedBy = event.senderId,
            targetPositionMs = target,
            nowMs = now(),
            operationId = syncId,
            durationMs = player.durationMs(),
        ) ?: return
        publishSyncState()

        // Validate the target against *this* stream's duration: different
        // providers and qualities legitimately have different lengths.
        var localTarget = target
        var reportReason: String? = null
        val duration = player.durationMs()
        if (duration != null && duration > 0 && target >= duration - SYNC_END_GUARD_MS) {
            localTarget = (duration - SYNC_END_GUARD_MS).coerceAtLeast(0L)
            reportReason = "target ${formatMs(target)} is past this stream's length (${formatMs(duration)})"
        }

        applyRemote {
            player.pause()
            player.setSpeed(1.0)
            player.seekTo(localTarget)
        }

        sessionScope?.launch {
            val prepared = waitForBuffer(BUFFER_TIMEOUT_MS)
            if (prepared) {
                syncNow.markPrepared(selfId, op.id)
                sendEvent(WatchPartyEventType.SYNC_PREPARED) { it.copy(syncId = op.id) }
            } else {
                val reason = reportReason ?: "buffering timeout"
                syncNow.markFailed(selfId, op.id, reason)
                sendEvent(WatchPartyEventType.SYNC_FAILED) { it.copy(syncId = op.id, reason = reason) }
            }
            publishSyncState()
        }
    }

    private fun onSyncPrepared(event: RoomEvent) {
        if (!syncNow.markPrepared(event.senderId, event.payload.syncId)) return
        publishSyncState()
        maybeCompleteSync()
    }

    private fun onSyncFailed(event: RoomEvent) {
        if (!syncNow.markFailed(event.senderId, event.payload.syncId, event.payload.reason)) return
        _effects.tryEmit(
            WatchPartyEffect.ShowMessage(
                "${event.senderName.ifBlank { "A participant" }} could not sync: ${event.payload.reason ?: "unknown"}",
            ),
        )
        publishSyncState()
        maybeCompleteSync()
    }

    private fun onSyncResume(event: RoomEvent) {
        if (!isAuthoritative(event.senderId)) return
        val target = event.payload.targetPositionMs
        syncNow.reset()
        publishSyncState()
        applyRemote {
            player.setSpeed(1.0)
            if (target != null) {
                syncEngine.clampRemoteSeek(target, player.durationMs())?.let { player.seekTo(it) }
            }
            player.play()
        }
        syncEngine.reset()
        authoritativeSnapshot = PlaybackSnapshot(
            positionMs = target ?: player.positionMs(),
            paused = false,
            durationMs = player.durationMs(),
            sentAtMs = event.sentAtMs,
        )
        _effects.tryEmit(WatchPartyEffect.ShowMessage("Synced — resuming together"))
    }

    // </editor-fold>

    // <editor-fold desc="Chat & reactions">

    fun sendChatMessage(text: String) {
        if (!isLive()) return
        val sanitized = RoomMessageCodec.sanitizeChatText(text)
        if (sanitized.isBlank()) return
        val messageId = UUID.randomUUID().toString()
        val message = ChatMessage(
            id = messageId,
            senderId = selfId,
            senderName = selfName(),
            text = sanitized,
            timestampMs = now(),
        )
        _state.update { current ->
            current.copy(chat = (current.chat + message).takeLast(MAX_CHAT_HISTORY))
        }
        sendEvent(WatchPartyEventType.CHAT_MESSAGE) {
            it.copy(messageId = messageId, text = sanitized, displayName = selfName(), participantId = selfId)
        }
    }

    /**
     * Queues an emoji reaction. Bursts are aggregated and flushed by the ticker so
     * repeated taps cost a fraction of the packets.
     */
    fun sendReaction(emoji: String, targetMessageId: String? = null) {
        if (!isLive()) return
        if (!ReactionAggregator.isAllowedEmoji(emoji)) return
        reactions.add(emoji, targetMessageId)
        // Aggregation happens on the ticker: a burst of taps becomes one packet.
        lastReactionQueuedAt = now()
    }

    private fun flushReactions() {
        val pending = reactions.drain()
        if (pending.isEmpty()) return
        pending.forEach { item ->
            val messageId = UUID.randomUUID().toString()
            sendEvent(WatchPartyEventType.EMOJI_REACTION) {
                it.copy(
                    emoji = item.emoji,
                    count = item.count,
                    messageId = messageId,
                    targetMessageId = item.targetMessageId,
                    displayName = selfName(),
                )
            }
            // Own events are filtered out of the inbound handler, so echo our own
            // reaction locally or the sender would never see their own tap.
            addLocalReaction(item.emoji, item.count, item.targetMessageId)
        }
    }

    private fun addLocalReaction(emoji: String, count: Int, targetMessageId: String?) {
        val reaction = ReactionEvent(
            senderId = selfId,
            senderName = RoomMessageCodec.sanitizeDisplayName(selfName()),
            emoji = emoji,
            count = count.coerceIn(1, MAX_REACTION_BURST),
            timestampMs = now(),
            targetMessageId = targetMessageId,
        )
        _state.update { it.copy(reactions = (it.reactions + reaction).takeLast(MAX_REACTION_HISTORY)) }
    }

    // </editor-fold>

    // <editor-fold desc="Presence & ticker">

    private fun sendPresence() {
        val current = participants[selfId] ?: return
        val paused = player.isPaused()
        val position = player.positionMs()
        participants[selfId] = current.copy(
            paused = paused,
            positionMs = position,
            updatedAt = now(),
            presenceSeq = nextSeq(),
        )
        lastPresenceSentAt = now()
        publishParticipants()
        sendEvent(WatchPartyEventType.PRESENCE) {
            it.copy(
                participantId = selfId,
                displayName = selfName(),
                paused = paused,
                positionMs = position,
                role = if (_state.value.isHost) ParticipantRole.HOST else ParticipantRole.GUEST,
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = sessionScope?.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        // Countdown: the *shared deadline* decides, never a per-device timer.
        when (val transition = readyCheck.tick(now())) {
            is ReadyCheckController.Transition.Elapsed -> {
                publishCountdown()
                onCountdownElapsed(transition.countdown)
            }
            else -> Unit
        }

        if (syncNow.timedOut(now())) failSync("Sync timed out")

        if (now() - lastPresenceSentAt >= PRESENCE_INTERVAL_MS) sendPresence()
        if (now() - lastReactionQueuedAt >= REACTION_FLUSH_MS) flushReactions()
        pruneStaleParticipants()
    }

    private fun onCountdownElapsed(countdown: ReadyCheckController.Countdown) {
        // Guests do nothing here: the host sends the single PLAY command.
        if (!_state.value.isHost) return

        // Re-validate readiness immediately before starting playback.
        if (!allPrepared(buildReadyStates(participants.keys.toList()))) {
            sendEvent(WatchPartyEventType.COUNTDOWN_CANCEL) {
                it.copy(countdownId = countdown.id, reason = "not ready")
            }
            countdownSuppressedUntil = now() + COUNTDOWN_RESTART_GUARD_MS
            _effects.tryEmit(WatchPartyEffect.ShowMessage("Countdown cancelled — someone is not ready"))
            return
        }

        val target = player.positionMs()
        applyRemote {
            player.seekTo(target)
            player.play()
        }
        syncEngine.reset()
        authoritativeSnapshot = PlaybackSnapshot(
            positionMs = target,
            paused = false,
            durationMs = player.durationMs(),
            sentAtMs = now(),
        )
        sendEvent(WatchPartyEventType.PLAY) {
            it.copy(positionMs = target, paused = false, countdownId = countdown.id)
        }
        _effects.tryEmit(WatchPartyEffect.PlaybackStarted)
    }

    private fun pruneStaleParticipants() {
        val nowMs = now()
        val stale = participants.values.filter {
            it.participantId != selfId &&
                it.updatedAt > 0 &&
                nowMs - it.updatedAt > DEFAULT_CONNECTION_TIMEOUT_MS
        }
        if (stale.isEmpty()) return
        stale.forEach { info ->
            participants.remove(info.participantId)
            manualReady.remove(info.participantId)
            router.forget(info.participantId)
        }
        publishParticipants()
        stale.forEach { info -> onParticipantRemoved(info.participantId, "${info.displayName} timed out") }
    }

    private fun onParticipantRemoved(id: String, reason: String) {
        // A departure during a countdown invalidates it: readiness changed.
        if (readyCheck.isCountingDown) {
            readyCheck.cancel(now())
            publishCountdown()
            sendEvent(WatchPartyEventType.COUNTDOWN_CANCEL) { it.copy(reason = reason) }
        }
        if (syncNow.isActive) {
            syncNow.markFailed(id, syncNow.operation?.id, reason)
            publishSyncState()
            maybeCompleteSync()
        }
        publishReadyStates()
    }

    private fun publishParticipants() {
        _state.update { it.copy(participants = participants.values.toList()) }
        publishReadyStates()
    }

    private fun publishReadyStates() {
        _state.update { current -> current.copy(readyStates = buildReadyStates(participants.keys.toList())) }
    }

    /**
     * Readiness is derived, never trusted blindly: a participant counts as ready
     * only when they said so *and* their episode is resolved locally. The local
     * player's buffering state is layered on top so a stalled player is not ready.
     */
    private fun buildReadyStates(ids: Collection<String>): Map<String, ReadyState> {
        return ids.associateWith { id ->
            val manual = manualReady[id] == true
            val resolved = if (id == selfId) player.localEpisode() != null && !player.isBuffering() else true
            when {
                !manual || !resolved -> ReadyState.NOT_READY
                id == selfId && !player.isPaused() -> ReadyState.PLAYING
                else -> ReadyState.READY
            }
        }
    }

    private fun allPrepared(states: Map<String, ReadyState>): Boolean =
        states.isNotEmpty() && states.values.all { it == ReadyState.READY || it == ReadyState.PLAYING }

    // </editor-fold>

    // <editor-fold desc="Playback helpers">

    private fun sendPlayback(
        type: WatchPartyEventType,
        paused: Boolean,
        positionMs: Long = player.positionMs(),
    ) {
        sendEvent(type) {
            it.copy(positionMs = positionMs, paused = paused, durationMs = player.durationMs())
        }
        authoritativeSnapshot = PlaybackSnapshot(
            positionMs = positionMs,
            paused = paused,
            durationMs = player.durationMs(),
            sentAtMs = now(),
        )
    }

    /** Pushes the full authoritative state (used on join / reconnect). */
    fun broadcastPlayback(force: Boolean) {
        if (!isLive() || !_state.value.isHost) return
        if (!force && authoritativeSnapshot != null) return
        sendPlayback(
            type = if (player.isPaused()) WatchPartyEventType.PAUSE else WatchPartyEventType.PLAY,
            paused = player.isPaused(),
        )
    }

    private inline fun applyRemote(block: () -> Unit) {
        isApplyingRemote = true
        try {
            block()
        } finally {
            isApplyingRemote = false
        }
    }

    private fun applyDecision(decision: PlaybackSyncEngine.Decision) {
        when (decision.action) {
            PlaybackSyncEngine.Action.NONE -> Unit
            PlaybackSyncEngine.Action.SEEK -> applyRemote { player.seekTo(decision.targetPositionMs) }
            PlaybackSyncEngine.Action.SPEED_UP, PlaybackSyncEngine.Action.SPEED_DOWN -> {
                val speed = decision.speed ?: return
                applyRemote { player.setSpeed(speed) }
                scheduleSpeedReset()
            }
            PlaybackSyncEngine.Action.SPEED_RESET -> {
                speedResetJob?.cancel()
                applyRemote { player.setSpeed(1.0) }
            }
        }
    }

    private fun scheduleSpeedReset() {
        speedResetJob?.cancel()
        speedResetJob = sessionScope?.launch {
            delay(SPEED_NUDGE_DURATION_MS)
            applyRemote { player.setSpeed(1.0) }
        }
    }

    private fun isLive(): Boolean = _state.value.active

    // </editor-fold>

    // <editor-fold desc="Incoming handlers">

    private fun handleEvent(event: RoomEvent) {
        if (event.senderId == selfId) return
        when (event.type) {
            WatchPartyEventType.PARTICIPANT_JOIN -> onParticipantJoin(event)
            WatchPartyEventType.PARTICIPANT_LEAVE -> onParticipantLeave(event)
            WatchPartyEventType.PRESENCE -> onPresence(event)
            WatchPartyEventType.PING, WatchPartyEventType.PONG -> Unit
            WatchPartyEventType.PLAY,
            WatchPartyEventType.PAUSE,
            WatchPartyEventType.SEEK,
            WatchPartyEventType.POSITION,
            -> onRemotePlayback(event)
            WatchPartyEventType.EPISODE_CHANGE -> onRemoteEpisode(event)
            WatchPartyEventType.READY, WatchPartyEventType.NOT_READY -> onRemoteReady(event)
            WatchPartyEventType.COUNTDOWN_START -> onCountdownStart(event)
            WatchPartyEventType.COUNTDOWN_CANCEL -> onCountdownCancel(event)
            WatchPartyEventType.SYNC_REQUEST -> onSyncRequest(event)
            WatchPartyEventType.SYNC_BEGIN -> onSyncBegin(event)
            WatchPartyEventType.SYNC_PREPARED -> onSyncPrepared(event)
            WatchPartyEventType.SYNC_FAILED -> onSyncFailed(event)
            WatchPartyEventType.SYNC_RESUME -> onSyncResume(event)
            WatchPartyEventType.CHAT_MESSAGE -> onChat(event)
            WatchPartyEventType.EMOJI_REACTION -> onReaction(event)
        }
    }

    private fun onParticipantJoin(event: RoomEvent) {
        val payload = event.payload
        val id = payload.participantId ?: event.senderId
        val info = ParticipantInfo(
            participantId = id,
            displayName = RoomMessageCodec.sanitizeDisplayName(payload.displayName ?: event.senderName),
            role = if (payload.role == ParticipantRole.HOST) ParticipantRole.HOST else ParticipantRole.GUEST,
            paused = payload.paused ?: true,
            positionMs = payload.positionMs ?: 0L,
            presenceSeq = event.seq,
            updatedAt = now(),
        )
        participants[id] = info
        adoptHostIfClaimed(id, info.role)
        publishParticipants()
        if (_state.value.isHost) {
            // Answer with role, episode and playback, in that order, so a
            // newcomer can identify the host and converge immediately.
            sendPresence()
            sendEvent(WatchPartyEventType.EPISODE_CHANGE) { it.copy(episode = player.localEpisode()) }
            broadcastPlayback(force = true)
        }
    }

    private fun onParticipantLeave(event: RoomEvent) {
        val id = event.payload.participantId ?: event.senderId
        participants.remove(id)
        manualReady.remove(id)
        router.forget(id)
        publishParticipants()
        onParticipantRemoved(id, "${event.senderName.ifBlank { "A participant" }} left")
    }

    private fun onPresence(event: RoomEvent) {
        val payload = event.payload
        val id = payload.participantId ?: event.senderId
        val existing = participants[id] ?: ParticipantInfo(
            participantId = id,
            displayName = RoomMessageCodec.sanitizeDisplayName(event.senderName),
        )
        val role = payload.role ?: existing.role
        participants[id] = existing.copy(
            displayName = payload.displayName?.let(RoomMessageCodec::sanitizeDisplayName) ?: existing.displayName,
            role = role,
            paused = payload.paused ?: existing.paused,
            positionMs = payload.positionMs ?: existing.positionMs,
            presenceSeq = event.seq,
            updatedAt = now(),
        )
        adoptHostIfClaimed(id, role)
        publishParticipants()
    }

    /**
     * Learns the host from any peer that advertises the host role.
     *
     * A guest that joins after the host's `PARTICIPANT_JOIN` still needs to know
     * who is authoritative; presence updates carry the role for exactly that.
     */
    private fun adoptHostIfClaimed(id: String, role: ParticipantRole) {
        if (role != ParticipantRole.HOST) return
        if (_state.value.isHost) return
        if (_state.value.hostId == id) return
        // First advertised host wins; a later impostor cannot take over mid-room.
        if (_state.value.hostId != null) return
        _state.update { it.copy(hostId = id) }
    }

    /**
     * Only the host's playback is authoritative. If the host id is still unknown
     * (join race) a peer is trusted when its presence advertised the host role.
     */
    private fun isAuthoritative(senderId: String): Boolean {
        if (senderId == selfId) return false
        val knownHost = _state.value.hostId
        if (knownHost != null) return senderId == knownHost
        // Host not discovered yet (join race): trust the advertised host role once.
        return participants[senderId]?.role == ParticipantRole.HOST
    }

    private fun onRemotePlayback(event: RoomEvent) {
        val payload = event.payload
        // Only the host is authoritative. Guests never drive playback directly;
        // recovery always goes through the explicit Sync Now flow.
        if (!isAuthoritative(event.senderId)) return

        when (event.type) {
            WatchPartyEventType.PLAY -> {
                syncEngine.reset()
                applyRemote {
                    val target = payload.positionMs?.let { syncEngine.clampRemoteSeek(it, player.durationMs()) }
                    if (target != null && syncEngine.shouldApplyRemotePosition(player.positionMs(), target, paused = false)) {
                        player.seekTo(target)
                    }
                    if (player.isPaused()) player.play()
                }
                authoritativeSnapshot = PlaybackSnapshot(
                    positionMs = payload.positionMs ?: player.positionMs(),
                    paused = false,
                    durationMs = payload.durationMs,
                    sentAtMs = event.sentAtMs,
                )
            }
            WatchPartyEventType.PAUSE -> {
                syncEngine.reset()
                applyRemote {
                    if (!player.isPaused()) player.pause()
                    payload.positionMs?.let { target ->
                        if (kotlin.math.abs(player.positionMs() - target) > PLAYBACK_PAUSE_TOLERANCE_MS) {
                            syncEngine.clampRemoteSeek(target, player.durationMs())?.let { player.seekTo(it) }
                        }
                    }
                }
                authoritativeSnapshot = PlaybackSnapshot(
                    positionMs = payload.positionMs ?: player.positionMs(),
                    paused = true,
                    durationMs = payload.durationMs,
                    sentAtMs = event.sentAtMs,
                )
            }
            WatchPartyEventType.SEEK -> {
                val target = payload.positionMs ?: return
                val clamped = syncEngine.clampRemoteSeek(target, player.durationMs()) ?: return
                applyRemote {
                    player.pause()
                    player.seekTo(clamped)
                    if (payload.paused == false) player.play()
                }
                syncEngine.reset()
                authoritativeSnapshot = PlaybackSnapshot(
                    positionMs = clamped,
                    paused = payload.paused ?: false,
                    durationMs = player.durationMs(),
                    sentAtMs = event.sentAtMs,
                )
            }
            WatchPartyEventType.POSITION -> {
                val target = payload.positionMs ?: return
                authoritativeSnapshot = PlaybackSnapshot(
                    positionMs = target,
                    paused = payload.paused ?: false,
                    durationMs = payload.durationMs,
                    sentAtMs = event.sentAtMs,
                )
            }
            else -> Unit
        }
    }

    private fun onRemoteEpisode(event: RoomEvent) {
        val episode = event.payload.episode ?: return
        val local = player.localEpisode()
        val (key, keySource) = player.localMediaKey() ?: (null to null)
        val match = EpisodeMatcher.match(episode, local, key, keySource)
        _state.update {
            it.copy(
                episode = episode,
                episodeMatch = match,
                warning = when (match) {
                    EpisodeMatch.EXACT -> null
                    EpisodeMatch.PROBABLE -> "Episode matched by title only — check you are on the right episode."
                    EpisodeMatch.UNRESOLVED -> "Open ${episode.displayName} to join in sync."
                    EpisodeMatch.MISMATCH -> "You are on a different episode — sync paused until you switch."
                },
            )
        }
    }

    private fun onRemoteReady(event: RoomEvent) {
        val id = event.payload.participantId ?: event.senderId
        val ready = event.type == WatchPartyEventType.READY
        manualReady[id] = ready
        if (!ready && readyCheck.isCountingDown) {
            cancelCountdown(reason = "${event.senderName.ifBlank { "A participant" }} is not ready")
            countdownSuppressedUntil = now() + COUNTDOWN_RESTART_GUARD_MS
        }
        publishReadyStates()
        maybeStartCountdown()
    }

    private fun onCountdownStart(event: RoomEvent) {
        if (!isAuthoritative(event.senderId)) return
        val payload = event.payload
        val countdownId = payload.countdownId ?: return
        val deadline = payload.deadlineMs ?: return
        if (readyCheck.start(countdownId, deadline, now()) is ReadyCheckController.Transition.Started) {
            publishCountdown()
        }
    }

    private fun onCountdownCancel(event: RoomEvent) {
        if (!readyCheck.isCountingDown) return
        readyCheck.cancel(now())
        publishCountdown()
        _effects.tryEmit(
            WatchPartyEffect.ShowMessage(
                "Countdown cancelled by ${event.senderName.ifBlank { "a participant" }}",
            ),
        )
    }

    private fun onSyncRequest(event: RoomEvent) {
        if (!_state.value.isHost) return
        beginSync(requestedBy = event.senderName.ifBlank { "Someone" })
    }

    private fun onChat(event: RoomEvent) {
        val payload = event.payload
        val text = payload.text?.let(RoomMessageCodec::sanitizeChatText) ?: return
        if (text.isBlank()) return
        val message = ChatMessage(
            id = payload.messageId ?: UUID.randomUUID().toString(),
            senderId = event.senderId,
            senderName = RoomMessageCodec.sanitizeDisplayName(payload.displayName ?: event.senderName),
            text = text,
            timestampMs = event.sentAtMs,
            replyToId = payload.targetMessageId,
        )
        _state.update { current ->
            if (current.chat.any { it.id == message.id }) {
                current
            } else {
                current.copy(chat = (current.chat + message).takeLast(MAX_CHAT_HISTORY))
            }
        }
    }

    private fun onReaction(event: RoomEvent) {
        val emoji = event.payload.emoji ?: return
        if (!ReactionAggregator.isAllowedEmoji(emoji)) return
        val id = event.payload.messageId ?: "${event.senderId}:${event.seq}"
        if (!seenReactionIds.add(id)) return
        if (seenReactionIds.size > MAX_SEEN_REACTIONS) seenReactionIds.clear()
        val reaction = ReactionEvent(
            senderId = event.senderId,
            senderName = RoomMessageCodec.sanitizeDisplayName(event.senderName),
            emoji = emoji,
            count = (event.payload.count ?: 1).coerceIn(1, MAX_REACTION_BURST),
            timestampMs = event.sentAtMs,
            targetMessageId = event.payload.targetMessageId,
        )
        _state.update { it.copy(reactions = (it.reactions + reaction).takeLast(MAX_REACTION_HISTORY)) }
    }

    // </editor-fold>

    // <editor-fold desc="Buffer waiting">

    /** Waits until mpv reports buffered (or the timeout elapses). Never blocks. */
    private suspend fun waitForBuffer(timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (!player.isBuffering()) return true
            delay(BUFFER_POLL_MS)
        }
        return !player.isBuffering()
    }

    // </editor-fold>

    private companion object {
        const val TICK_MS = 250L
        const val HOST_POSITION_INTERVAL_MS = 1_500L
        const val PRESENCE_INTERVAL_MS = 4_000L
        const val SYNC_HOST_GATHER_MS = 900L
        const val SYNC_POLL_MS = 250L
        const val SYNC_END_GUARD_MS = 1_500L
        const val BUFFER_TIMEOUT_MS = 12_000L
        const val BUFFER_POLL_MS = 200L
        const val SPEED_NUDGE_DURATION_MS = 4_000L
        const val PLAYBACK_PAUSE_TOLERANCE_MS = 1_000L
        const val COUNTDOWN_RESTART_GUARD_MS = 3_000L
        const val MAX_CHAT_HISTORY = 200
        const val MAX_REACTION_BURST = 30
        const val MAX_REACTION_HISTORY = 80
        const val MAX_SEEN_REACTIONS = 256
        const val REACTION_FLUSH_MS = 450L
    }
}

/** Formats milliseconds as `m:ss`, used in sync status messages. */
internal fun formatMs(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
