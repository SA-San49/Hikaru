package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerSentEventParserTest {

    @Test
    fun `accepts standard data lines with and without optional whitespace`() {
        assertEquals("{\"message\":\"hello\"}", parseServerSentEventData("data: {\"message\":\"hello\"}"))
        assertEquals("[DONE]", parseServerSentEventData("data:[DONE]"))
        assertEquals("event", parseServerSentEventData("data:event"))
        assertEquals("event", parseServerSentEventData("data: event"))
        assertEquals("event", parseServerSentEventData("data:  event"))
    }

    @Test
    fun `ignores event metadata comments and blank lines`() {
        assertNull(parseServerSentEventData(": keepalive"))
        assertNull(parseServerSentEventData("event: content_block_delta"))
        assertNull(parseServerSentEventData("id: 3"))
        assertNull(parseServerSentEventData(""))
        assertNull(parseServerSentEventData(" data: value"))
    }
}
