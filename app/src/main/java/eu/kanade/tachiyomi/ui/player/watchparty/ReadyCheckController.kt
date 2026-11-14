package eu.kanade.tachiyomi.ui.player.watchparty

import kotlin.math.max

/**
 * Shared ready-check state machine.
 *
 * There is exactly **one authoritative countdown** in a room: the host elects
 * it, broadcasts `COUNTDOWN_START` with a countdown id and a shared deadline,
 * and every other participant derives the remaining seconds from that deadline.
 * No device runs its own independent timer.
 *
 * Any participant may cancel. Cancellations, disconnects, late joiners and
 * stale/duplicate `COUNTDOWN_START` events are all handled here.
 */
class ReadyCheckController {

    data class Countdown(
        val id: String,
        val deadlineMs: Long,
        val startedAtMs: Long,
    ) {
        fun remainingMs(nowMs: Long): Long = max(0L, deadlineMs - nowMs)

        fun remainingSeconds(nowMs: Long): Int =
            ((remainingMs(nowMs) + 999L) / 1000L).toInt()
    }

    sealed interface Transition {
        /** Enter into a countdown. */
        data class Started(val countdown: Countdown) : Transition

        /** Countdown was cancelled; [reason] is user-facing. */
        data class Cancelled(val reason: String) : Transition

        /** Deadline passed for a still-valid countdown: play now. */
        data class Elapsed(val countdown: Countdown) : Transition

        /** Ignored input. */
        data class Rejected(val reason: String) : Transition
    }

    var countdown: Countdown? = null
        private set

    /** Last countdown id that was cancelled, used to reject delayed duplicates. */
    private var cancelledId: String? = null

    fun reset() {
        countdown = null
        cancelledId = null
    }

    val isCountingDown: Boolean get() = countdown != null

    /**
     * Starts a countdown, idempotently: re-delivering the same id is rejected so
     * the deadline never drifts.
     */
    fun start(
        countdownId: String,
        deadlineMs: Long,
        nowMs: Long,
        durationSeconds: Int = READY_COUNTDOWN_SECONDS,
    ): Transition {
        if (countdownId.isBlank()) return Transition.Rejected("missing-id")
        if (countdownId == cancelledId) return Transition.Rejected("already-cancelled")
        if (countdown != null) {
            return when (countdown?.id) {
                countdownId -> Transition.Rejected("duplicate")
                else -> Transition.Rejected("countdown-active")
            }
        }
        // A stale start (deadline already in the past) must never trigger playback.
        if (deadlineMs <= nowMs) return Transition.Rejected("stale-deadline")

        val maxDuration = nowMs + durationSeconds * 1000L + SLACK_MS
        if (deadlineMs > maxDuration) return Transition.Rejected("deadline-too-far")

        val countdown = Countdown(countdownId, deadlineMs, nowMs)
        this.countdown = countdown
        return Transition.Started(countdown)
    }

    fun cancel(nowMs: Long, reason: String = "cancelled"): Transition {
        val active = countdown ?: return Transition.Rejected("no-countdown")
        countdown = null
        cancelledId = active.id
        return Transition.Cancelled(reason)
    }

    /** Called periodically (and on resume) to detect that a deadline has passed. */
    fun tick(nowMs: Long): Transition {
        val active = countdown ?: return Transition.Rejected("no-countdown")
        return if (nowMs >= active.deadlineMs) {
            countdown = null
            Transition.Elapsed(active)
        } else {
            Transition.Rejected("waiting")
        }
    }

    /**
     * Whether a countdown may start given the current readiness snapshot.
     * Every *active* participant must be ready; disconnected participants are
     * excluded by the caller rather than reported as not ready.
     */
    fun canStart(readyStates: Collection<ReadyState>): Boolean {
        if (readyStates.isEmpty()) return false
        return readyStates.all { it == ReadyState.READY || it == ReadyState.PLAYING }
    }

    /** Re-validated immediately before playback starts. */
    fun revalidateBeforeStart(readyStates: Collection<ReadyState>): Boolean = canStart(readyStates)

    private companion object {
        /** Allow minor clock skew between peers when validating deadlines. */
        const val SLACK_MS = 1_000L
    }
}
