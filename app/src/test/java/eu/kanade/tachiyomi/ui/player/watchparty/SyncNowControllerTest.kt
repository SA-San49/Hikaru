package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncNowControllerTest {

    private val config = SyncNowController.Config()
    private val now = 5_000_000L

    private fun controller() = SyncNowController(config)

    private fun start(controller: SyncNowController, target: Long = 120_000, at: Long = now) =
        controller.request(
            requestedBy = "guest-1",
            targetPositionMs = target,
            nowMs = at,
            operationId = "sync-1",
            durationMs = 1_440_000,
        )

    @Test
    fun `a request creates a preparing operation targeting the host position`() {
        val controller = controller()
        val op = start(controller)
        assertNotNull(op)
        assertEquals(120_000, op!!.targetPositionMs)
        assertEquals(SyncNowController.Phase.PREPARING, controller.phase)
        assertTrue(controller.isActive)
    }

    @Test
    fun `targets past the local duration are clamped`() {
        val controller = controller()
        val op = start(controller, target = 1_500_000)
        assertEquals(1_440_000 - 1_000, op!!.targetPositionMs)
    }

    @Test
    fun `concurrent requests merge instead of starting a second operation`() {
        val controller = controller()
        val first = start(controller)
        val second = controller.request("guest-2", 130_000, now + 100, "sync-2", 1_440_000)
        assertNotNull(first)
        assertNull(second)
        assertEquals("sync-1", controller.operation?.id)
    }

    @Test
    fun `the very first request is never blocked by an unset cooldown`() {
        val controller = controller()
        // Regression: a Long.MIN_VALUE sentinel made `now - last` overflow and
        // silently refuse every Sync Now for the whole session.
        assertNotNull(start(controller, at = 0L))
    }

    @Test
    fun `requests during the cooldown are refused`() {
        val controller = controller()
        val op = start(controller)
        controller.finish(now + 1_000)
        assertNull(start(controller, at = now + 1_000))
        assertNotNull(start(controller, at = now + 1_000 + config.cooldownMs))
    }

    @Test
    fun `resume waits for every reachable participant`() {
        val controller = controller()
        start(controller)
        controller.markPrepared("host", "sync-1")
        controller.markPrepared("guest-1", "sync-1")

        assertFalse(controller.shouldResume(listOf("host", "guest-1", "guest-2"), now + 500, hostPrepared = true))
        assertTrue(controller.shouldResume(listOf("host", "guest-1"), now + 500, hostPrepared = true))
    }

    @Test
    fun `silent participants stop blocking after the response timeout`() {
        val controller = controller()
        start(controller)
        controller.markPrepared("host", "sync-1")
        assertFalse(controller.shouldResume(listOf("host", "offline-guest"), now + 1_000, hostPrepared = true))
        assertTrue(
            controller.shouldResume(
                listOf("host", "offline-guest"),
                now + config.responseTimeoutMs,
                hostPrepared = true,
            ),
        )
        assertEquals(listOf("offline-guest"), controller.pendingParticipants(listOf("host", "offline-guest")))
    }

    @Test
    fun `a failed participant is skipped and never blocks the room`() {
        val controller = controller()
        start(controller)
        controller.markPrepared("host", "sync-1")
        controller.markFailed("broken-guest", "sync-1", "cannot seek")
        assertTrue(controller.shouldResume(listOf("host", "broken-guest"), now + 500, hostPrepared = true))
    }

    @Test
    fun `stale sync ids are ignored`() {
        val controller = controller()
        start(controller)
        assertFalse(controller.markPrepared("guest-1", "some-old-sync"))
        assertFalse(controller.markFailed("guest-1", "some-old-sync", "stale"))
        assertTrue(controller.markPrepared("guest-1", "sync-1"))
    }

    @Test
    fun `the host itself must finish preparing before resuming`() {
        val controller = controller()
        start(controller)
        controller.markPrepared("guest-1", "sync-1")
        assertFalse(controller.shouldResume(listOf("host", "guest-1"), now + 100, hostPrepared = false))
        assertTrue(
            controller.shouldResume(
                listOf("host", "guest-1"),
                now + config.prepareTimeoutMs,
                hostPrepared = false,
            ),
        )
    }

    @Test
    fun `operations self abort when they outlive their budget`() {
        val controller = controller()
        start(controller)
        assertFalse(controller.timedOut(now + config.maxOperationMs - 1))
        assertTrue(controller.timedOut(now + config.maxOperationMs + 1))
    }

    @Test
    fun `abort returns the operation id and clears state`() {
        val controller = controller()
        start(controller)
        assertEquals("sync-1", controller.abort("timeout"))
        assertNull(controller.operation)
        assertEquals(SyncNowController.Phase.IDLE, controller.phase)
    }

    @Test
    fun `reset allows a fresh operation with a new id`() {
        val controller = controller()
        start(controller)
        controller.reset()
        val next = controller.request("guest-1", 90_000, now, "sync-2", 1_440_000)
        assertEquals("sync-2", next?.id)
    }
}
