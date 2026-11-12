package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomMessageCodecTest {

    @Test
    fun `events round trip through the codec`() {
        val event = RoomEvent(
            type = WatchPartyEventType.CHAT_MESSAGE,
            roomId = "AB34CD56",
            senderId = "p-1",
            senderName = "Rin",
            seq = 12,
            sentAtMs = 1_700_000_000_000,
            payload = EventPayload(messageId = "m-1", text = "hello there", displayName = "Rin"),
        )
        val decoded = RoomMessageCodec.decode(RoomMessageCodec.encode(event))
        assertEquals(event, decoded)
    }

    @Test
    fun `unknown fields from a newer peer are ignored`() {
        val raw = """
            {"type":"play","roomId":"AB34CD56","senderId":"p-1","seq":3,
             "brandNewField":{"nested":[1,2,3]},"payload":{"positionMs":5000,"paused":false}}
        """.trimIndent()
        val decoded = RoomMessageCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(WatchPartyEventType.PLAY, decoded!!.type)
        assertEquals(5000, decoded.payload.positionMs)
    }

    @Test
    fun `garbage input is rejected without throwing`() {
        assertNull(RoomMessageCodec.decode(null))
        assertNull(RoomMessageCodec.decode(""))
        assertNull(RoomMessageCodec.decode("not json at all"))
        assertNull(RoomMessageCodec.decode("""{"type":"nonsense"}"""))
        assertNull(RoomMessageCodec.decode("""{"roomId":"AB34CD56"}"""))
        assertNull(RoomMessageCodec.decode("[1,2,3]"))
        assertNull(RoomMessageCodec.decode("{"))
    }

    @Test
    fun `oversized frames are rejected`() {
        val huge = "x".repeat(RoomMessageCodec.MAX_FRAME_BYTES + 10)
        assertNull(RoomMessageCodec.decode(huge))
    }

    @Test
    fun `chat text is trimmed, collapsed and capped`() {
        assertEquals("hi there", RoomMessageCodec.sanitizeChatText("  hi   there \n"))
        val long = "a".repeat(RoomMessageCodec.MAX_CHAT_LENGTH + 100)
        assertEquals(RoomMessageCodec.MAX_CHAT_LENGTH, RoomMessageCodec.sanitizeChatText(long).length)
    }

    @Test
    fun `display names are sanitised`() {
        assertEquals("Guest", RoomMessageCodec.sanitizeDisplayName(null))
        assertEquals("Guest", RoomMessageCodec.sanitizeDisplayName("   "))
        assertEquals("Rin", RoomMessageCodec.sanitizeDisplayName("  Rin  "))
        assertEquals(24, RoomMessageCodec.sanitizeDisplayName("n".repeat(80)).length)
        assertTrue(RoomMessageCodec.sanitizeDisplayName("a\u0000b").none { it.code < 0x20 })
    }

    @Test
    fun `emoji chat survives the codec`() {
        val event = RoomEvent(
            type = WatchPartyEventType.CHAT_MESSAGE,
            roomId = "AB34CD56",
            senderId = "p-2",
            payload = EventPayload(messageId = "m-9", text = "\uD83D\uDD25\uD83D\uDE02 nice"),
        )
        val decoded = RoomMessageCodec.decode(RoomMessageCodec.encode(event))
        assertEquals("\uD83D\uDD25\uD83D\uDE02 nice", decoded?.payload?.text)
    }
}
