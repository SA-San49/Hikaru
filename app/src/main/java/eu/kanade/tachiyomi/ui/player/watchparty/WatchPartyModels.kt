package eu.kanade.tachiyomi.ui.player.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version of the wire format used by the Watch Party room transport.
 *
 * Bumped only when the meaning of existing fields changes; new optional fields
 * may be added at any time because every peer decodes with `ignoreUnknownKeys`.
 */
const val WATCH_PARTY_PROTOCOL_VERSION = 1

/** Length of the shared ready-check countdown. */
const val READY_COUNTDOWN_SECONDS = 5

/** How long a participant may stay silent before being considered stalled. */
const val DEFAULT_CONNECTION_TIMEOUT_MS = 20_000L

/**
 * Identity of the episode the room is watching.
 *
 * Every participant resolves the stream with their *own* extension, so this
 * deliberately carries metadata only: never a stream URL, headers, cookies or
 * provider credentials.
 */
@Serializable
data class EpisodeIdentity(
    /** Display title of the anime, used for manual matching. */
    val animeTitle: String = "",
    /** Stable provider-independent key (AniList/MAL id when available). */
    val mediaKey: String? = null,
    /** Source of [mediaKey], e.g. `anilist`, `mal`, or `local`. */
    val mediaKeySource: String? = null,
    /** Episode number, `-1` when unknown (specials/unknown numbering). */
    val episodeNumber: Double = -1.0,
    /** Episode title as shown by the host. */
    val episodeTitle: String = "",
    /** Optional season number to disambiguate split cours. */
    val seasonNumber: Double? = null,
    /** Raw stream length reported by the *host*, used only for sanity checks. */
    val durationMs: Long? = null,
) {
    val displayName: String
        get() {
            val number = if (episodeNumber >= 0) {
                val rounded = episodeNumber
                if (rounded == rounded.toLong().toDouble()) {
                    "Episode ${rounded.toLong()}"
                } else {
                    "Episode $rounded"
                }
            } else {
                "Unknown episode"
            }
            return when {
                episodeTitle.isBlank() -> number
                else -> "$number — $episodeTitle"
            }
        }
}

/**
 * Result of comparing the room's [EpisodeIdentity] with what a peer actually
 * managed to resolve locally.
 */
enum class EpisodeMatch {
    /** Same tracking id and episode number. */
    EXACT,

    /** Same anime but the episode number could not be confirmed. */
    PROBABLE,

    /** The peer has nothing loaded for the room's episode. */
    UNRESOLVED,

    /** The peer is playing a different episode entirely. */
    MISMATCH,
}

/** How a participant joins or is admitted to a room. */
@Serializable
enum class ParticipantRole {
    /** Created the room; owns playback authority. */
    HOST,

    /** Joined with the room code. */
    GUEST,
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * Readiness of a participant for the shared countdown.
 *
 * A participant is [READY] only when their episode is resolved *and* their
 * local player finished buffering; the second half is derived, not sent, so a
 * peer cannot lie about being prepared.
 */
enum class ReadyState {
    NOT_READY,
    READY,
    PLAYING,
}

/** Participant as seen by the room. */
@Serializable
data class ParticipantInfo(
    val participantId: String,
    val displayName: String,
    val role: ParticipantRole = ParticipantRole.GUEST,
    /** Last reported local playback position, in milliseconds. */
    val positionMs: Long = 0L,
    /** Last reported local paused flag. */
    val paused: Boolean = true,
    /** Monotonic counter used to order presence updates. */
    val presenceSeq: Long = 0L,
    /** Wall-clock time (sender side) of the last presence update. */
    val updatedAt: Long = 0L,
)

/** A chat message carried over the room transport. */
@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestampMs: Long,
    /** Optional id of another message this one reacts to. */
    val replyToId: String? = null,
)

/** A reaction event, aggregated by emoji before being broadcast. */
@Serializable
data class ReactionEvent(
    val senderId: String,
    val senderName: String,
    val emoji: String,
    val count: Int = 1,
    val timestampMs: Long = 0L,
    val targetMessageId: String? = null,
)

/**
 * A playback snapshot moved between peers.
 *
 * [positionMs] is always paired with [sentAtMs] so the receiver can project
 * forward and compensate for network latency.
 */
@Serializable
data class PlaybackSnapshot(
    val positionMs: Long,
    val paused: Boolean,
    val durationMs: Long? = null,
    /** Wall-clock time of the sender, in milliseconds. */
    val sentAtMs: Long = 0L,
    /** Monotonic authority sequence; only increasing snapshots are applied. */
    val sequence: Long = 0L,
)

/** Transport envelopes. */
@Serializable
enum class WatchPartyEventType {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("seek") SEEK,
    @SerialName("position") POSITION,
    @SerialName("ready") READY,
    @SerialName("not_ready") NOT_READY,
    @SerialName("countdown_start") COUNTDOWN_START,
    @SerialName("countdown_cancel") COUNTDOWN_CANCEL,
    @SerialName("sync_request") SYNC_REQUEST,
    @SerialName("sync_begin") SYNC_BEGIN,
    @SerialName("sync_prepared") SYNC_PREPARED,
    @SerialName("sync_failed") SYNC_FAILED,
    @SerialName("sync_resume") SYNC_RESUME,
    @SerialName("chat_message") CHAT_MESSAGE,
    @SerialName("emoji_reaction") EMOJI_REACTION,
    @SerialName("participant_join") PARTICIPANT_JOIN,
    @SerialName("participant_leave") PARTICIPANT_LEAVE,
    @SerialName("presence") PRESENCE,
    @SerialName("episode_change") EPISODE_CHANGE,
    @SerialName("ping") PING,
    @SerialName("pong") PONG,
}

/**
 * Single room event envelope.
 *
 * [seq] is assigned by the sender per participant stream and lets receivers
 * drop duplicate or stale deliveries.  [sentAtMs] is the sender's wall clock and
 * is used for latency projection (all peers are NTP-synced to within ~a second;
 * anything worse only affects how aggressive drift correction becomes).
 */
@Serializable
data class RoomEvent(
    val type: WatchPartyEventType,
    val roomId: String,
    val senderId: String,
    val senderName: String = "",
    val seq: Long = 0L,
    val sentAtMs: Long = 0L,
    val protocolVersion: Int = WATCH_PARTY_PROTOCOL_VERSION,
    val payload: EventPayload = EventPayload(),
)

/**
 * Flat payload bag so event modelling stays versionable and forward compatible.
 * Any field that is irrelevant for a given [RoomEvent.type] stays at its default.
 */
@Serializable
data class EventPayload(
    val positionMs: Long? = null,
    val paused: Boolean? = null,
    val durationMs: Long? = null,

    // Ready check
    val countdownId: String? = null,
    val deadlineMs: Long? = null,

    // Sync Now
    val syncId: String? = null,
    val targetPositionMs: Long? = null,
    val targetSentAtMs: Long? = null,
    val reason: String? = null,

    // Chat & reactions
    val messageId: String? = null,
    val text: String? = null,
    val emoji: String? = null,
    val count: Int? = null,
    val targetMessageId: String? = null,

    // Presence / episode
    val participantId: String? = null,
    val displayName: String? = null,
    val role: ParticipantRole? = null,
    val episode: EpisodeIdentity? = null,
)
