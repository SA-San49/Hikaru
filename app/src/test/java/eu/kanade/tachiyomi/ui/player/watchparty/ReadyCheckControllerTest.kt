package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadyCheckControllerTest {

    private val now = 1_000_000L

    private fun started(controller: ReadyCheckController, id: String = "cd-1", now: Long = this.now) =
        controller.start(id, deadlineMs = now + 5_000, nowMs = now)

    @Test
    fun `countdown starts with the shared deadline`() {
        val controller = ReadyCheckController()
        val transition = started(controller)
        val started = assertInstanceOf(ReadyCheckController.Transition.Started::class.java, transition)
        assertEquals("cd-1", started.countdown.id)
        assertEquals(now + 5_000, started.countdown.deadlineMs)
        assertTrue(controller.isCountingDown)
    }

    @Test
    fun `equal replicas do not restart the countdown`() {
        val controller = ReadyCheckController()
        assertInstanceOf(ReadyCheckController.Transition.Started::class.java, started(controller))
        assertEquals(
            ReadyCheckController.Transition.Rejected("duplicate"),
            controller.start("cd-1", now + 5_000, now + 100),
        )
        // The deadline never drifts from repeated delivery.
        assertEquals(now + 5_000, controller.countdown?.deadlineMs)
    }

    @Test
    fun `a second different countdown is rejected while one is active`() {
        val controller = ReadyCheckController()
        started(controller)
        assertEquals(
            ReadyCheckController.Transition.Rejected("countdown-active"),
            controller.start("cd-2", now + 9_000, now + 100),
        )
    }

    @Test
    fun `stale deadlines are rejected so late events can never start playback`() {
        val controller = ReadyCheckController()
        assertEquals(
            ReadyCheckController.Transition.Rejected("stale-deadline"),
            controller.start("cd-old", deadlineMs = now - 1, nowMs = now),
        )
        assertFalse(controller.isCountingDown)
    }

    @Test
    fun `absurdly distant deadlines are rejected`() {
        val controller = ReadyCheckController()
        assertEquals(
            ReadyCheckController.Transition.Rejected("deadline-too-far"),
            controller.start("cd-far", deadlineMs = now + 60_000, nowMs = now),
        )
    }

    @Test
    fun `cancelled countdown can never fire`() {
        val controller = ReadyCheckController()
        started(controller)
        assertInstanceOf(ReadyCheckController.Transition.Cancelled::class.java, controller.cancel(now + 1_000))
        assertNull(controller.countdown)
        // Even past the original deadline nothing happens.
        assertEquals(
            ReadyCheckController.Transition.Rejected("no-countdown"),
            controller.tick(now + 10_000),
        )
        // A delayed duplicate of the cancelled start is refused.
        assertEquals(
            ReadyCheckController.Transition.Rejected("already-cancelled"),
            controller.start("cd-1", now + 5_000, now + 2_000),
        )
    }

    @Test
    fun `tick elapses exactly once the deadline passes`() {
        val controller = ReadyCheckController()
        started(controller)
        assertEquals(
            ReadyCheckController.Transition.Rejected("waiting"),
            controller.tick(now + 4_999),
        )
        val elapsed = assertInstanceOf(
            ReadyCheckController.Transition.Elapsed::class.java,
            controller.tick(now + 5_000),
        )
        assertEquals("cd-1", elapsed.countdown.id)
        assertFalse(controller.isCountingDown)
        // Repeated ticks do not fire playback again.
        assertEquals(
            ReadyCheckController.Transition.Rejected("no-countdown"),
            controller.tick(now + 6_000),
        )
    }

    @Test
    fun `remaining seconds are derived from the deadline`() {
        val controller = ReadyCheckController()
        started(controller)
        assertEquals(5, controller.countdown!!.remainingSeconds(now))
        assertEquals(3, controller.countdown!!.remainingSeconds(now + 2_000))
        assertEquals(1, controller.countdown!!.remainingSeconds(now + 4_500))
        assertEquals(0, controller.countdown!!.remainingSeconds(now + 9_000))
    }

    @Test
    fun `readiness requires every participant`() {
        val controller = ReadyCheckController()
        assertTrue(controller.canStart(listOf(ReadyState.READY, ReadyState.READY)))
        assertTrue(controller.canStart(listOf(ReadyState.READY, ReadyState.PLAYING)))
        assertFalse(controller.canStart(listOf(ReadyState.READY, ReadyState.NOT_READY)))
        assertFalse(controller.canStart(emptyList()))
    }

    @Test
    fun `revalidating before start catches a late not-ready`() {
        val controller = ReadyCheckController()
        assertTrue(controller.revalidateBeforeStart(listOf(ReadyState.READY)))
        assertFalse(controller.revalidateBeforeStart(listOf(ReadyState.NOT_READY)))
    }

    @Test
    fun `reset clears state for a new room`() {
        val controller = ReadyCheckController()
        started(controller)
        controller.reset()
        assertNull(controller.countdown)
        assertInstanceOf(
            ReadyCheckController.Transition.Started::class.java,
            controller.start("cd-1", now + 5_000, now),
        )
    }
}
