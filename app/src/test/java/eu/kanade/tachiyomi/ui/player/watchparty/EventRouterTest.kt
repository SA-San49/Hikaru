package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventRouterTest {

    private val roomId = "AB34CD56"
    private val router = EventRouter { roomId }

    private fun event(
        type: WatchPartyEventType = WatchPartyEventType.PLAY,
        sender: String = "peer-a",
        seq: Long = 1L,
        room: String = roomId,
        version: Int = WATCH_PARTY_PROTOCOL_VERSION,
        payload: EventPayload = EventPayload(),
    ) = RoomEvent(
        type = type,
        roomId = room,
        senderId = sender,
        seq = seq,
        protocolVersion = version,
        payload = payload,
    )

    private fun delivered(roomEvent: RoomEvent): Boolean = router.route(roomEvent) is EventRouter.Result.Deliver

    @Test
    fun `events for the active room are delivered once`() {
        assertTrue(delivered(event(seq = 1)))
    }

    @Test
    fun `events from another room are dropped`() {
        assertEquals(
            EventRouter.Result.Dropped("wrong-room"),
            router.route(event(room = "ZZ99ZZ99")),
        )
    }

    @Test
    fun `duplicate and stale sequence numbers are dropped`() {
        assertTrue(delivered(event(seq = 5)))
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(seq = 5)),
        )
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(seq = 4)),
        )
        assertTrue(delivered(event(seq = 6)))
    }

    @Test
    fun `sequence tracking is per sender`() {
        assertTrue(delivered(event(sender = "a", seq = 10)))
        assertTrue(delivered(event(sender = "b", seq = 1)))
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(sender = "a", seq = 9)),
        )
    }

    @Test
    fun `a leave keeps the sequence window closed to replayed frames`() {
        assertTrue(delivered(event(sender = "a", seq = 8)))
        router.forget("a")
        // A relay may redeliver an old join after the leave; it must not resurrect.
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(sender = "a", seq = 3)),
        )
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(sender = "a", seq = 0, type = WatchPartyEventType.CHAT_MESSAGE)),
        )
    }

    @Test
    fun `a genuine rejoin reopens the sequence window once`() {
        assertTrue(delivered(event(sender = "a", seq = 8)))
        router.forget("a")
        // A reconnecting client restarts its counter and announces itself again.
        assertTrue(
            delivered(event(sender = "a", seq = 0, type = WatchPartyEventType.PARTICIPANT_JOIN)),
        )
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(sender = "a", seq = 0, type = WatchPartyEventType.PARTICIPANT_JOIN)),
        )
        assertTrue(delivered(event(sender = "a", seq = 1)))
    }

    @Test
    fun `clearing the router drops all remembered state`() {
        assertTrue(delivered(event(sender = "a", seq = 8)))
        router.clearAll()
        assertTrue(delivered(event(sender = "a", seq = 0)))
    }

    @Test
    fun `newer protocol versions are rejected`() {
        assertEquals(
            EventRouter.Result.Dropped("protocol-too-new"),
            router.route(event(version = WATCH_PARTY_PROTOCOL_VERSION + 1)),
        )
    }

    @Test
    fun `blank sender is rejected`() {
        assertEquals(EventRouter.Result.Dropped("no-sender"), router.route(event(sender = "")))
    }

    @Test
    fun `duplicate chat messages are dropped even with a new seq`() {
        val payload = EventPayload(messageId = "m-1", text = "hi")
        assertTrue(delivered(event(seq = 1, payload = payload)))
        assertEquals(
            EventRouter.Result.Dropped("stale-seq"),
            router.route(event(seq = 1, payload = payload)),
        )
        // A replayed message id with a fresh seq must also be rejected.
        assertEquals(
            EventRouter.Result.Dropped("duplicate-message"),
            router.route(event(seq = 2, payload = payload)),
        )
    }

    @Test
    fun `chat without an id is rejected`() {
        assertEquals(
            EventRouter.Result.Dropped("no-message-id"),
            router.route(event(type = WatchPartyEventType.CHAT_MESSAGE, seq = 3)),
        )
    }

    @Test
    fun `duplicate reactions by the same sender are dropped`() {
        val payload = EventPayload(emoji = "\uD83D\uDD25", messageId = "r-1")
        assertTrue(delivered(event(type = WatchPartyEventType.EMOJI_REACTION, seq = 1, payload = payload)))
        assertEquals(
            EventRouter.Result.Dropped("duplicate-reaction"),
            router.route(event(type = WatchPartyEventType.EMOJI_REACTION, seq = 2, payload = payload)),
        )
    }

    @Test
    fun `pings bypass sequence validation`() {
        assertTrue(delivered(event(type = WatchPartyEventType.PING, seq = 1)))
        assertTrue(delivered(event(type = WatchPartyEventType.PING, seq = 1)))
    }

    @Test
    fun `without an active room everything is dropped`() {
        val inactive = EventRouter { null }
        assertEquals(EventRouter.Result.Dropped("no-room"), inactive.route(event()))
    }

    @Test
    fun `reset clears sequence state`() {
        assertTrue(delivered(event(sender = "a", seq = 9)))
        router.reset()
        assertTrue(delivered(event(sender = "a", seq = 1)))
    }
}
