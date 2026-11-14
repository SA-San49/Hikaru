package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Aggregates rapid emoji reactions so a burst of taps becomes a single network
 * event instead of one packet per tap.
 *
 * The aggregator is time based (`flushWindowMs`) and bounded, so it cannot grow
 * without limit even if a peer spams reactions.
 */
class ReactionAggregator(
    private val flushWindowMs: Long = 450L,
    private val maxPendingKinds: Int = 8,
) {

    data class Pending(
        val emoji: String,
        val count: Int,
        val targetMessageId: String?,
    )

    private val pending = LinkedHashMap<String, Pending>()

    val isEmpty: Boolean get() = pending.isEmpty()

    /** Adds one reaction and returns the aggregated counts to broadcast. */
    fun add(emoji: String, targetMessageId: String? = null): List<Pending> {
        if (emoji.isBlank()) return emptyList()
        val key = "$emoji|${targetMessageId.orEmpty()}"
        val existing = pending[key]
        pending[key] = Pending(
            emoji = emoji,
            count = (existing?.count ?: 0) + 1,
            targetMessageId = targetMessageId,
        )
        if (pending.size > maxPendingKinds) {
            val oldest = pending.keys.firstOrNull() ?: return emptyList()
            pending.remove(oldest)
        }
        return pending.values.toList()
    }

    /** Removes and returns everything queued. */
    fun drain(): List<Pending> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.values.toList()
        pending.clear()
        return out
    }

    companion object {
        /** Quick reactions offered in the UI. */
        val QUICK_REACTIONS = listOf("\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2D", "\uD83D\uDD25", "\uD83D\uDE31", "\uD83D\uDC4F", "\uD83D\uDC80")

        /**
         * Validates an incoming emoji: refuses payloads that are not a small
         * emoji sequence so a hostile peer cannot push arbitrary large strings
         * into the UI.
         */
        fun isAllowedEmoji(value: String): Boolean {
            if (value.isEmpty() || value.length > MAX_EMOJI_LENGTH) return false
            return value.any { it.code > 0x2000 } || value in QUICK_REACTIONS
        }

        private const val MAX_EMOJI_LENGTH = 16
    }
}
