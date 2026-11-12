package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackSyncEngineTest {

    private val config = PlaybackSyncEngine.Config()
    private fun engine() = PlaybackSyncEngine(config)

    @Test
    fun `small drift is ignored so playback is not thrashed`() {
        val decision = engine().evaluate(expected = 100_000, actual = 100_300, paused = false, buffering = false, nowMs = 0)
        assertEquals(PlaybackSyncEngine.Action.NONE, decision.action)
        assertEquals(300, decision.driftMs)
    }

    @Test
    fun `medium drift triggers a soft speed correction not a seek`() {
        val decision = engine().evaluate(expected = 100_000, actual = 98_000, paused = false, buffering = false, nowMs = 10_000)
        assertEquals(PlaybackSyncEngine.Action.SPEED_UP, decision.action)
        assertTrue(decision.speed!! > 1.0)

        val ahead = engine().evaluate(expected = 98_000, actual = 100_000, paused = false, buffering = false, nowMs = 10_000)
        assertEquals(PlaybackSyncEngine.Action.SPEED_DOWN, ahead.action)
        assertTrue(ahead.speed!! < 1.0)
    }

    @Test
    fun `large drift seeks with a cooldown`() {
        val engine = engine()
        val first = engine.evaluate(expected = 300_000, actual = 200_000, paused = false, buffering = false, nowMs = 1_000)
        assertEquals(PlaybackSyncEngine.Action.SEEK, first.action)
        assertEquals(300_000, first.targetPositionMs)

        // Immediately after, the cooldown must suppress another seek.
        val second = engine.evaluate(expected = 400_000, actual = 200_000, paused = false, buffering = false, nowMs = 2_000)
        assertEquals(PlaybackSyncEngine.Action.NONE, second.action)
        // ...until it expires.
        val third = engine.evaluate(
            expected = 400_000,
            actual = 200_000,
            paused = false,
            buffering = false,
            nowMs = 1_000 + config.seekCooldownMs + 1,
        )
        assertEquals(PlaybackSyncEngine.Action.SEEK, third.action)
    }

    @Test
    fun `paused or buffering never requests a seek`() {
        val engine = engine()
        assertEquals(
            PlaybackSyncEngine.Action.SPEED_RESET,
            engine.evaluate(300_000, 0, paused = true, buffering = false, nowMs = 0).action,
        )
        assertEquals(
            PlaybackSyncEngine.Action.SPEED_RESET,
            engine.evaluate(300_000, 0, paused = false, buffering = true, nowMs = 0).action,
        )
    }

    @Test
    fun `target is clamped to the local duration which may differ per provider`() {
        val engine = engine()
        val decision = engine.evaluate(
            expected = 1_500_000,
            actual = 100_000,
            paused = false,
            buffering = false,
            nowMs = 0,
            duration = 1_440_000,
        )
        assertEquals(PlaybackSyncEngine.Action.SEEK, decision.action)
        assertEquals(1_440_000 - config.endGuardMs, decision.targetPositionMs)
    }

    @Test
    fun `projection forwards a playing snapshot and freezes a paused one`() {
        val engine = engine()
        val playing = PlaybackSnapshot(positionMs = 10_000, paused = false, sentAtMs = 1_000)
        assertEquals(14_000, engine.projectPosition(playing, nowMs = 4_000))

        val paused = playing.copy(paused = true)
        assertEquals(10_000, engine.projectPosition(paused, nowMs = 4_000))
    }

    @Test
    fun `projection never runs away after a long silence`() {
        val engine = engine()
        val snapshot = PlaybackSnapshot(positionMs = 10_000, paused = false, sentAtMs = 0)
        // 10 minutes of silence must not project 10 minutes forward.
        assertTrue(engine.projectPosition(snapshot, nowMs = 600_000) <= 10_000 + 15_000)
    }

    @Test
    fun `remote position is applied only when it matters`() {
        val engine = engine()
        assertFalse(engine.shouldApplyRemotePosition(100_000, 100_500, paused = false))
        assertTrue(engine.shouldApplyRemotePosition(100_000, 120_000, paused = false))
        assertFalse(engine.shouldApplyRemotePosition(100_000, 120_000, paused = true))
    }

    @Test
    fun `remote seeks are clamped or refused safely`() {
        val engine = engine()
        assertNull(engine.clampRemoteSeek(-5, durationMs = 1_000_000))
        assertEquals(500_000, engine.clampRemoteSeek(500_000, durationMs = 1_000_000))
        assertEquals(
            1_000_000 - config.endGuardMs,
            engine.clampRemoteSeek(1_000_000, durationMs = 1_000_000),
        )
        // Unknown duration: pass through, but never negative.
        assertEquals(2_000_000, engine.clampRemoteSeek(2_000_000, durationMs = null))
    }

    @Test
    fun `reset clears cooldowns so a fresh sync applies immediately`() {
        val engine = engine()
        engine.evaluate(300_000, 0, paused = false, buffering = false, nowMs = 1_000)
        engine.reset()
        val afterReset = engine.evaluate(300_000, 0, paused = false, buffering = false, nowMs = 1_100)
        assertEquals(PlaybackSyncEngine.Action.SEEK, afterReset.action)
    }
}
