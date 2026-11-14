package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactionAggregatorTest {

    @Test
    fun `repeated taps collapse into one counted reaction`() {
        val aggregator = ReactionAggregator()
        repeat(4) { aggregator.add("\uD83D\uDD25") }
        val pending = aggregator.drain()
        assertEquals(1, pending.size)
        assertEquals(4, pending.first().count)
        assertTrue(aggregator.isEmpty)
    }

    @Test
    fun `different emoji are counted separately`() {
        val aggregator = ReactionAggregator()
        aggregator.add("\uD83D\uDD25")
        aggregator.add("\u2764\uFE0F")
        aggregator.add("\uD83D\uDD25")
        val pending = aggregator.drain().associate { it.emoji to it.count }
        assertEquals(2, pending["\uD83D\uDD25"])
        assertEquals(1, pending["\u2764\uFE0F"])
    }

    @Test
    fun `draining twice yields nothing the second time`() {
        val aggregator = ReactionAggregator()
        aggregator.add("\uD83D\uDC80")
        assertEquals(1, aggregator.drain().size)
        assertTrue(aggregator.drain().isEmpty())
    }

    @Test
    fun `blank emoji are ignored`() {
        val aggregator = ReactionAggregator()
        assertTrue(aggregator.add("").isEmpty())
        assertTrue(aggregator.isEmpty)
    }

    @Test
    fun `pending kinds are bounded`() {
        val aggregator = ReactionAggregator(maxPendingKinds = 3)
        listOf("\uD83D\uDD25", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDC80").forEach { aggregator.add(it) }
        assertTrue(aggregator.drain().size <= 3)
    }

    @Test
    fun `only emoji-like payloads are accepted from the network`() {
        assertTrue(ReactionAggregator.isAllowedEmoji("\uD83D\uDD25"))
        assertTrue(ReactionAggregator.isAllowedEmoji("\u2764\uFE0F"))
        assertFalse(ReactionAggregator.isAllowedEmoji(""))
        assertFalse(ReactionAggregator.isAllowedEmoji("hello"))
        assertFalse(ReactionAggregator.isAllowedEmoji("x".repeat(40)))
    }

    @Test
    fun `quick reactions expose the documented set`() {
        assertEquals(7, ReactionAggregator.QUICK_REACTIONS.size)
        assertTrue(ReactionAggregator.QUICK_REACTIONS.contains("\uD83D\uDE31"))
    }
}
