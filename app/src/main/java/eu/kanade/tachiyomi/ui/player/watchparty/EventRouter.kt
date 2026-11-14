package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Central room event router.
 *
 * Every incoming [RoomEvent] goes through one place that:
 *  - rejects events from a different room or protocol;
 *  - rejects duplicate / stale events per sender using `seq`;
 *  - never throws on malformed payloads (safe parsing happens earlier, this
 *    layer only validates semantics).
 *
 * Playback, ready check, Sync Now, chat and reactions are then dispatched to
 * their own handlers by [WatchPartyEngine].
 */
class EventRouter(
    private val roomIdProvider: () -> String?,
) {

    /** Highest accepted `seq` per sender. */
    private val lastSequence = HashMap<String, Long>()

    /** Senders known to have left; a later join restarts their sequence window. */
    private val departed = HashSet<String>()

    /** Ring buffer of recently accepted ids for chat/reaction de-duplication. */
    private val seenMessageIds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (added && size > MAX_TRACKED_IDS) {
                val iterator = iterator()
                iterator.next()
                iterator.remove()
            }
            return added
        }
    }

    data class Accepted(val event: RoomEvent)

    sealed interface Result {
        data class Deliver(val event: RoomEvent) : Result
        data class Dropped(val reason: String) : Result
    }

    fun reset() = clearAll()

    /**
     * Marks a participant as gone.
     *
     * The sequence watermark is deliberately kept: a relay may redeliver the old
     * `PARTICIPANT_JOIN` after a leave, and re-opening the sequence window would
     * let that stale frame resurrect a departed participant. The next *fresh*
     * join from that sender (a new session starting again at seq 1) is the only
     * event allowed to reset the window.
     */
    fun forget(senderId: String) {
        departed += senderId
        lastSequence[senderId] = lastSequence[senderId] ?: 0L
    }

    /** Full reset; only used when entering a brand new room. */
    fun clearAll() {
        lastSequence.clear()
        departed.clear()
        seenMessageIds.clear()
    }

    fun route(event: RoomEvent): Result {
        val roomId = roomIdProvider()
            ?: return Result.Dropped("no-room")
        if (!RoomCode.same(event.roomId, roomId)) return Result.Dropped("wrong-room")
        if (event.protocolVersion > WATCH_PARTY_PROTOCOL_VERSION) {
            return Result.Dropped("protocol-too-new")
        }
        if (event.senderId.isBlank()) return Result.Dropped("no-sender")

        // Pings carry no ordering guarantees and must always go through.
        if (event.type == WatchPartyEventType.PING || event.type == WatchPartyEventType.PONG) {
            return Result.Deliver(event)
        }

        val last = lastSequence[event.senderId]
        if (last != null && event.seq <= last) {
            // A participant that left and genuinely reconnected restarts its
            // counter; their fresh join reopens the window exactly once.
            if (event.type == WatchPartyEventType.PARTICIPANT_JOIN && event.senderId in departed) {
                departed.remove(event.senderId)
            } else {
                return Result.Dropped("stale-seq")
            }
        }
        lastSequence[event.senderId] = event.seq

        when (event.type) {
            WatchPartyEventType.CHAT_MESSAGE -> {
                val id = event.payload.messageId ?: return Result.Dropped("no-message-id")
                if (!seenMessageIds.add("c:$id")) return Result.Dropped("duplicate-message")
            }
            WatchPartyEventType.EMOJI_REACTION -> {
                val id = event.payload.messageId
                if (id != null && !seenMessageIds.add("r:$id:${event.senderId}")) {
                    return Result.Dropped("duplicate-reaction")
                }
            }
            else -> Unit
        }

        return Result.Deliver(event)
    }

    private companion object {
        const val MAX_TRACKED_IDS = 512
    }
}
