package eu.kanade.tachiyomi.ui.player.watchparty

import kotlin.math.abs

/**
 * Decides how a participant should follow the room's authoritative playback,
 * without owning any player object. All methods are pure and deterministic so
 * the policy can be unit tested.
 *
 * Design rules (from the feature spec):
 *  - the **host** is authoritative, never the slowest participant;
 *  - small drift is tolerated and never causes a seek;
 *  - drift correction is rate limited so the player is not thrashed;
 *  - remote events are applied with an explicit re-entrancy guard owned by the
 *    caller, so this class only answers "what should happen".
 */
class PlaybackSyncEngine(
    private val config: Config = Config(),
) {

    data class Config(
        /** Drift below this is ignored entirely. */
        val ignoreDriftMs: Long = 900,
        /** Drift above this is corrected with a seek instead of a speed nudge. */
        val seekDriftMs: Long = 4_000,
        /** Minimum gap between two corrective seeks. */
        val seekCooldownMs: Long = 8_000,
        /** Minimum gap between two corrective speed nudges. */
        val speedCooldownMs: Long = 6_000,
        /** Speed offset applied for small drift; kept subtle on purpose. */
        val softCorrectionSpeed: Double = 0.05,
        /** Never start playback closer than this to the end of the stream. */
        val endGuardMs: Long = 3_000,
    )

    enum class Action {
        /** Nothing to do. */
        NONE,
        /** Local playback is behind/ahead far enough to warrant a seek. */
        SEEK,

        /** Temporarily play slightly faster / slower to converge. */
        SPEED_UP,
        SPEED_DOWN,

        /** Restore the normal playback rate. */
        SPEED_RESET,
    }

    data class Decision(
        val action: Action,
        val targetPositionMs: Long = 0L,
        val driftMs: Long = 0L,
        val speed: Double? = null,
        val reason: String = "",
    )

    private var lastSeekAtMs: Long = Long.MIN_VALUE
    private var lastSpeedAtMs: Long = Long.MIN_VALUE

    fun reset() {
        lastSeekAtMs = Long.MIN_VALUE
        lastSpeedAtMs = Long.MIN_VALUE
    }

    /** Epoch-millis projection of a snapshot, compensating for transport delay. */
    fun projectPosition(snapshot: PlaybackSnapshot, nowMs: Long, clockOffsetMs: Long = 0L): Long {
        if (snapshot.paused) return snapshot.positionMs
        val elapsed = (nowMs + clockOffsetMs - snapshot.sentAtMs).coerceIn(0L, MAX_PROJECTION_MS)
        return snapshot.positionMs + elapsed
    }

    /**
     * @param expected authoritative position the local player should be showing
     * @param actual current local position
     * @param paused whether the local player is paused
     * @param buffering local buffer stall (never seek while buffering)
     * @param nowMs monotonic-ish clock used for cooldowns
     * @param duration local stream duration; differs per provider and may be null
     */
    fun evaluate(
        expected: Long,
        actual: Long,
        paused: Boolean,
        buffering: Boolean,
        nowMs: Long,
        duration: Long? = null,
    ): Decision {
        if (paused || buffering) {
            return Decision(Action.SPEED_RESET, reason = "paused-or-buffering")
        }

        val drift = expected - actual
        val absDrift = abs(drift)

        if (absDrift <= config.ignoreDriftMs) {
            return Decision(Action.NONE, driftMs = drift, reason = "within-tolerance")
        }

        if (absDrift > config.seekDriftMs) {
            if (nowMs - lastSeekAtMs < config.seekCooldownMs) {
                return Decision(Action.NONE, driftMs = drift, reason = "seek-cooldown")
            }
            val target = if (duration != null && duration > 0) {
                expected.coerceIn(0L, maxOf(0L, duration - config.endGuardMs))
            } else {
                expected.coerceAtLeast(0L)
            }
            // A seek cannot help when the target is unreachable locally.
            if (duration != null && duration > 0 && target <= 0 && expected > 0) {
                return Decision(Action.NONE, driftMs = drift, reason = "unreachable-target")
            }
            lastSeekAtMs = nowMs
            lastSpeedAtMs = nowMs
            return Decision(Action.SEEK, targetPositionMs = target, driftMs = drift, reason = "drift")
        }

        // Soft correction: nudge the playback rate for a few seconds.
        if (nowMs - lastSpeedAtMs < config.speedCooldownMs) {
            return Decision(Action.NONE, driftMs = drift, reason = "speed-cooldown")
        }
        lastSpeedAtMs = nowMs
        val speed = if (drift > 0) 1.0 + config.softCorrectionSpeed else 1.0 - config.softCorrectionSpeed
        return Decision(
            action = if (drift > 0) Action.SPEED_UP else Action.SPEED_DOWN,
            driftMs = drift,
            speed = speed,
            reason = "soft-correction",
        )
    }

    /**
     * Whether a remote "position" update should be applied at all.
     * The host broadcasts often; guests should not seek on every packet.
     */
    fun shouldApplyRemotePosition(
        currentPositionMs: Long,
        remotePositionMs: Long,
        paused: Boolean,
    ): Boolean {
        if (paused) return false
        return abs(currentPositionMs - remotePositionMs) > config.seekDriftMs
    }

    /**
     * Whether a remote seek should be honoured. Guards against a peer seeking
     * outside this device's stream length, which is legal with different
     * providers.
     */
    fun clampRemoteSeek(requestedMs: Long, durationMs: Long?): Long? {
        if (requestedMs < 0) return null
        if (durationMs == null || durationMs <= 0) return requestedMs
        if (requestedMs >= durationMs - config.endGuardMs) {
            // Different stream durations: clamp instead of refusing so the room
            // keeps working near the end of an episode.
            return (durationMs - config.endGuardMs).coerceAtLeast(0L)
        }
        return requestedMs
    }

    private companion object {
        /** A peer that disappears for longer than this is treated as paused. */
        const val MAX_PROJECTION_MS = 15_000L
    }
}
