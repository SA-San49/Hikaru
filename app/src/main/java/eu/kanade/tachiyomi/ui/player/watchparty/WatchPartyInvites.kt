package eu.kanade.tachiyomi.ui.player.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hand-off for Watch Party invite links.
 *
 * A `hikaru://watchparty/<CODE>` or `https://hikaru.app/watchparty/<CODE>` link
 * can arrive while no player exists yet, so it cannot be delivered straight to a
 * [WatchPartyController]. The link lands here instead and the player consumes it
 * once it is composed.
 *
 * The invite only *pre-fills* the join field and opens the sheet: nothing joins
 * automatically and no networking starts until the user confirms.
 */
object WatchPartyInvites {

    private val _pending = MutableStateFlow<String?>(null)

    /** Non-null while an unclaimed invite is waiting. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Stores [rawCode] if it is a valid room code. Returns whether it was stored. */
    fun post(rawCode: String?): Boolean {
        val code = RoomCode.normalize(rawCode.orEmpty()) ?: return false
        _pending.value = code
        return true
    }

    /** Stores a code parsed out of arbitrary invite text/link. */
    fun postInvite(raw: String?): Boolean = post(WatchPartyInviteParser.parseRoomCode(raw))

    /** Returns the waiting code and clears it. */
    fun take(): String? = _pending.getAndUpdate()

    /** Drops any waiting invite (used when the player closes). */
    fun clear() {
        _pending.value = null
    }

    private fun MutableStateFlow<String?>.getAndUpdate(): String? {
        val current = value
        value = null
        return current
    }
}
