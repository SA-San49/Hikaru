package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedOutboxTest {

    @Test
    fun `frames come out in the order they went in`() {
        val outbox = BoundedOutbox(capacity = 4)
        outbox.offer("a")
        outbox.offer("b")
        outbox.offer("c")
        assertEquals("a", outbox.poll())
        assertEquals("b", outbox.poll())
        assertEquals("c", outbox.poll())
        assertNull(outbox.poll())
        assertTrue(outbox.isEmpty)
    }

    @Test
    fun `an offer that fits reports plain success`() {
        val outbox = BoundedOutbox(capacity = 4)
        assertEquals(BoundedOutbox.Result.Queued, outbox.offer("a"))
        assertEquals(0L, outbox.droppedFrames)
    }

    @Test
    fun `a full queue drops the oldest frame not the newest`() {
        val outbox = BoundedOutbox(capacity = 3)
        listOf("one", "two", "three", "four").forEach { outbox.offer(it) }
        // "one" is the casualty; the newest frame must survive.
        assertEquals("two", outbox.poll())
        assertEquals("three", outbox.poll())
        assertEquals("four", outbox.poll())
        assertNull(outbox.poll())
    }

    @Test
    fun `drops are reported so a slow guest cannot be silently ignored`() {
        val outbox = BoundedOutbox(capacity = 2)
        outbox.offer("a")
        outbox.offer("b")
        val result = outbox.offer("c")
        assertTrue(result is BoundedOutbox.Result.QueuedAfterDropping)
        assertEquals(1, (result as BoundedOutbox.Result.QueuedAfterDropping).count)
        assertEquals(1L, outbox.droppedFrames)
    }

    @Test
    fun `the queue never exceeds its capacity`() {
        val outbox = BoundedOutbox(capacity = 5)
        repeat(500) { outbox.offer("frame-$it") }
        assertEquals(5, outbox.size)
        assertEquals(495L, outbox.droppedFrames)
    }

    @Test
    fun `a slow guest is marked stalled only after sustained loss`() {
        val outbox = BoundedOutbox(capacity = 2)
        outbox.offer("a")
        outbox.offer("b")
        outbox.offer("c") // one drop: normal jitter
        assertFalse(outbox.stalled)

        repeat(BoundedOutbox.STALL_THRESHOLD.toInt()) { outbox.offer("x$it") }
        assertTrue(outbox.stalled)
    }

    @Test
    fun `stats can be reset when a peer recovers`() {
        val outbox = BoundedOutbox(capacity = 1)
        outbox.offer("a")
        outbox.offer("b")
        assertTrue(outbox.droppedFrames > 0)
        outbox.resetStats()
        assertEquals(0L, outbox.droppedFrames)
        assertFalse(outbox.stalled)
    }

    @Test
    fun `an oversized frame is rejected without flushing the queue`() {
        val outbox = BoundedOutbox(capacity = 3)
        outbox.offer("keep-1")
        outbox.offer("keep-2")
        val huge = "x".repeat(BoundedOutbox.MAX_FRAME_CHARS + 1)
        assertEquals(BoundedOutbox.Result.Rejected, outbox.offer(huge))
        assertEquals("keep-1", outbox.poll())
        assertEquals("keep-2", outbox.poll())
    }

    @Test
    fun `clearing drops queued frames but keeps nothing stale`() {
        val outbox = BoundedOutbox(capacity = 4)
        outbox.offer("a")
        outbox.offer("b")
        outbox.clear()
        assertTrue(outbox.isEmpty)
        assertNull(outbox.poll())
    }

    @Test
    fun `capacity must be positive`() {
        var threw = false
        try {
            BoundedOutbox(capacity = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
