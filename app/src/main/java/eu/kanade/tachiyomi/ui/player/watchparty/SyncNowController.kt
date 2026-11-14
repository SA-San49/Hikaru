package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * One-tap "Sync Now" state machine.
 *
 * Sync Now is an explicit recovery operation, not the primary synchronisation
 * mechanism. Flow:
 *
 *  1. any participant requests a sync (`SYNC_REQUEST`);
 *  2. the host validates it and captures its own authoritative position;
 *  3. concurrent requests are merged into the single running operation;
 *  4. `SYNC_BEGIN` broadcasts the target + operation id;
 *  5. participants seek, buffer and report `SYNC_PREPARED` / `SYNC_FAILED`;
 *  6. once every reachable participant is prepared (or timed out) the host sends
 *     `SYNC_RESUME` and everyone restarts together.
 *
 * The operation always targets the **host's** position — never the slowest
 * participant — and a participant who cannot seek never blocks the room forever.
 */
class SyncNowController(
    private val config: Config = Config(),
) {

    data class Config(
        /** How long a participant may take to prepare before being skipped. */
        val prepareTimeoutMs: Long = 12_000L,
        /** How long to wait for a peer's `SYNC_PREPARED` after `SYNC_BEGIN`. */
        val responseTimeoutMs: Long = 6_000L,
        /** Maximum lifetime of one operation, after which it self-aborts. */
        val maxOperationMs: Long = 45_000L,
        /** Cooldown after a completed operation, prevents request storms. */
        val cooldownMs: Long = 4_000L,
    )

    data class Operation(
        val id: String,
        val targetPositionMs: Long,
        val targetSentAtMs: Long,
        val requestedBy: String,
        val startedAtMs: Long,
        /** Participant ids that already confirmed local readiness. */
        val prepared: Set<String> = emptySet(),
        /** Participant ids that reported an unrecoverable failure. */
        val failed: Set<String> = emptySet(),
    ) {
        val attempted: Set<String> get() = prepared + failed
    }

    enum class Phase {
        IDLE,
        /** Waiting for every participant; host schedules the resume. */
        PREPARING,
        /** Resume broadcast sent, operation complete. */
        RESUMING,
    }

    var phase: Phase = Phase.IDLE
        private set

    var operation: Operation? = null
        private set

    /**
     * When the last operation ended, or `null` if none ran yet.
     *
     * A sentinel like [Long.MIN_VALUE] would make `nowMs - lastFinishedAtMs`
     * overflow to a negative value and silently block every future sync.
     */
    private var lastFinishedAtMs: Long? = null

    fun reset() {
        phase = Phase.IDLE
        operation = null
        lastFinishedAtMs = null
    }

    val isActive: Boolean get() = phase == Phase.PREPARING

    /**
     * Returns the operation to broadcast, or `null` when the request must be
     * rejected (cooldown / already active). Concurrent requests during an active
     * operation are intentionally *merged*: the caller receives `null` and should
     * simply not start a second one.
     */
    fun request(
        requestedBy: String,
        targetPositionMs: Long,
        nowMs: Long,
        operationId: String,
        durationMs: Long? = null,
    ): Operation? {
        if (isActive) return null
        val sinceLast = lastFinishedAtMs?.let { nowMs - it }
        if (sinceLast != null && sinceLast < config.cooldownMs) return null

        val target = targetPositionMs.coerceAtLeast(0L).let { position ->
            if (durationMs != null && durationMs > 0) {
                position.coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L))
            } else {
                position
            }
        }

        val op = Operation(
            id = operationId,
            targetPositionMs = target,
            targetSentAtMs = nowMs,
            requestedBy = requestedBy,
            startedAtMs = nowMs,
        )
        operation = op
        phase = Phase.PREPARING
        return op
    }

    /** Records a participant's preparation result. */
    fun markPrepared(participantId: String, syncId: String?): Boolean {
        val active = operation ?: return false
        if (!matches(active, syncId)) return false
        operation = active.copy(prepared = active.prepared + participantId)
        return true
    }

    fun markFailed(participantId: String, syncId: String?, reason: String? = null): Boolean {
        val active = operation ?: return false
        if (!matches(active, syncId)) return false
        operation = active.copy(failed = active.failed + participantId)
        return true
    }

    /**
     * Whether the host should send `SYNC_RESUME` now.
     *
     * @param activeParticipants everyone currently connected, excluding the host
     * @param nowMs current time
     * @param hostPrepared whether the host itself finished seeking/buffering
     */
    fun shouldResume(activeParticipants: Collection<String>, nowMs: Long, hostPrepared: Boolean): Boolean {
        val active = operation ?: return false
        if (phase != Phase.PREPARING) return false

        if (!hostPrepared && nowMs - active.startedAtMs < config.prepareTimeoutMs) return false

        val waiting = activeParticipants.filterNot { it in active.attempted }
        if (waiting.isEmpty()) return true

        // Past the response timeout, unreachable peers stop blocking the room.
        return nowMs - active.startedAtMs >= config.responseTimeoutMs
    }

    /** Participants that still have not answered; surfaced in the UI. */
    fun pendingParticipants(activeParticipants: Collection<String>): List<String> {
        val active = operation ?: return emptyList()
        return activeParticipants.filterNot { it in active.attempted }
    }

    fun abort(reason: String): String? {
        val active = operation ?: return null
        operation = null
        phase = Phase.IDLE
        lastFinishedAtMs = active.startedAtMs
        return active.id
    }

    fun finish(nowMs: Long): Operation? {
        val active = operation ?: return null
        operation = null
        phase = Phase.IDLE
        lastFinishedAtMs = nowMs
        return active
    }

    /** Self-abort guard so a lost participant can never wedge the room. */
    fun timedOut(nowMs: Long): Boolean {
        val active = operation ?: return false
        return nowMs - active.startedAtMs > config.maxOperationMs
    }

    private fun matches(active: Operation, syncId: String?): Boolean {
        // A missing id is accepted (older peers) but a different id is stale.
        if (syncId == null) return true
        return active.id == syncId
    }
}
