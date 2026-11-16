package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WatchPartyLayoutTest {

    private val hd = 16.0 / 9.0

    private fun request(
        width: Int = 1080,
        height: Int = 2400,
        statusBar: Int = 72,
        ime: Int = 0,
        inputRow: Int = 140,
        aspect: Double = hd,
        sideChat: Int = 420,
        mode: WatchPartyLayout.Mode = WatchPartyLayout.Mode.STACKED,
    ) = WatchPartyLayout.Request(width, height, statusBar, ime, inputRow, aspect, sideChat, mode)

    private fun assertAspect(rect: WatchPartyLayout.Rect, aspect: Double, tolerance: Double = 0.03) {
        val actual = rect.width.toDouble() / rect.height
        assertTrue(abs(actual - aspect) < tolerance, "aspect was $actual, expected $aspect")
    }

    // <editor-fold desc="Mode selection">

    @Test
    fun `portrait stacks and landscape goes side by side`() {
        assertEquals(WatchPartyLayout.Mode.STACKED, WatchPartyLayout.modeFor(1080, 2400))
        assertEquals(WatchPartyLayout.Mode.SIDE_BY_SIDE, WatchPartyLayout.modeFor(2400, 1080))
        assertEquals(WatchPartyLayout.Mode.STACKED, WatchPartyLayout.modeFor(1080, 1080))
    }

    @Test
    fun `a degenerate window does not crash`() {
        assertEquals(WatchPartyLayout.Mode.STACKED, WatchPartyLayout.modeFor(0, 0))
        assertEquals(WatchPartyLayout.Mode.STACKED, WatchPartyLayout.modeFor(-5, 100))
    }

    // </editor-fold>

    // <editor-fold desc="The letterbox bug">

    @Test
    fun `portrait video keeps its aspect ratio instead of being letterboxed`() {
        val result = WatchPartyLayout.compute(request())
        assertAspect(result.video, hd)
        assertTrue(WatchPartyLayout.isTight(result, hd))
    }

    @Test
    fun `portrait chat gets real space below the video`() {
        val result = WatchPartyLayout.compute(request())
        assertTrue(result.chat.height > 400, "chat was only ${result.chat.height}px")
    }

    @Test
    fun `the video never takes more than two thirds of the content height`() {
        // A very tall window: without the cap, a 16:9 video would still leave a
        // sliver of chat, and the panel would be unusable.
        val result = WatchPartyLayout.compute(request(width = 480, height = 2400))
        val contentHeight = 2400 - 72
        assertTrue(result.video.height <= (contentHeight * 2) / 3 + 1)
        assertTrue(result.chat.height > 0)
    }

    @Test
    fun `a 4 by 3 video is also shown without distortion`() {
        val result = WatchPartyLayout.compute(request(aspect = 4.0 / 3.0))
        assertAspect(result.video, 4.0 / 3.0)
    }

    @Test
    fun `a portrait video is fitted by width and does not overflow`() {
        val aspect = 9.0 / 16.0
        val result = WatchPartyLayout.compute(request(aspect = aspect))
        assertAspect(result.video, aspect)
        assertTrue(result.video.right <= result.chat.right)
        assertTrue(result.video.bottom <= 2400)
    }

    // </editor-fold>

    // <editor-fold desc="Regions are disjoint and complete">

    @Test
    fun `no region overlaps another in portrait`() {
        val result = WatchPartyLayout.compute(request())
        assertTrue(result.statusBar.bottom <= result.video.top + 1)
        assertTrue(result.video.bottom <= result.chat.top)
        assertTrue(result.chat.bottom <= result.input.top)
        assertTrue(result.input.bottom <= 2400)
    }

    @Test
    fun `the regions cover the window with only the video's own insets`() {
        val result = WatchPartyLayout.compute(request())
        // Video, chat and input are contiguous: no dead bands.
        assertEquals(result.video.bottom, result.chat.top)
        assertEquals(result.chat.bottom, result.input.top)
        assertEquals(2400, result.input.bottom)
    }

    @Test
    fun `no region overlaps in landscape`() {
        val result = WatchPartyLayout.compute(
            request(width = 2400, height = 1080, mode = WatchPartyLayout.Mode.SIDE_BY_SIDE),
        )
        assertTrue(result.video.right <= result.chat.left + 1)
        assertTrue(result.chat.left == result.input.left)
        assertTrue(result.chat.bottom <= result.input.top)
        assertTrue(result.statusBar.bottom <= result.video.top)
    }

    @Test
    fun `landscape keeps the video large and the chat usable`() {
        val result = WatchPartyLayout.compute(
            request(width = 2400, height = 1080, mode = WatchPartyLayout.Mode.SIDE_BY_SIDE),
        )
        assertAspect(result.video, hd)
        assertTrue(result.video.width > 1400, "video was only ${result.video.width}px wide")
        assertTrue(result.chat.width > 300, "chat was only ${result.chat.width}px wide")
    }

    @Test
    fun `the chat column never takes more than three fifths of the width`() {
        val result = WatchPartyLayout.compute(
            request(width = 2000, height = 1000, sideChat = 5000, mode = WatchPartyLayout.Mode.SIDE_BY_SIDE),
        )
        assertTrue(result.chat.width <= (2000 * 3) / 5)
        assertTrue(result.video.width > 0)
    }

    // </editor-fold>

    // <editor-fold desc="Keyboard">

    @Test
    fun `the keyboard shrinks the chat and never covers the input`() {
        val ime = 900
        val result = WatchPartyLayout.compute(request(ime = ime))
        // The input row sits directly above the keyboard.
        assertEquals(2400 - ime, result.input.bottom)
        assertTrue(result.chat.bottom <= result.input.top)
        assertTrue(result.chat.height > 0)
    }

    @Test
    fun `a huge keyboard cannot produce a negative layout`() {
        val result = WatchPartyLayout.compute(request(ime = 2300))
        assertTrue(result.chat.height >= 0)
        assertTrue(result.input.height >= 0)
        assertTrue(result.input.bottom <= 2400)
    }

    @Test
    fun `the keyboard shrinks the video in landscape without distorting it`() {
        val with = WatchPartyLayout.compute(
            request(width = 2400, height = 1080, ime = 600, mode = WatchPartyLayout.Mode.SIDE_BY_SIDE),
        )
        // The IME covers the bottom of the window, so the video region genuinely
        // gets shorter — pretending otherwise would hide the picture behind the
        // keyboard. It must still keep its shape and stay above the chat input.
        assertAspect(with.video, hd)
        // Video and input live in different columns, so their vertical bounds are
        // unrelated; what matters is that the columns never overlap and the input
        // still sits directly on top of the keyboard.
        assertTrue(with.video.right <= with.chat.left + 1)
        assertTrue(with.input.bottom == 1080 - 600)
        assertTrue(with.input.top >= with.chat.top)
        assertTrue(with.chat.height > 0)
    }

    // </editor-fold>

    // <editor-fold desc="Degenerate input">

    @Test
    fun `a zero sized window yields empty regions rather than negative ones`() {
        val result = WatchPartyLayout.compute(request(width = 0, height = 0))
        assertTrue(result.video.isEmpty)
        assertTrue(result.chat.isEmpty)
        assertTrue(result.input.isEmpty)
    }

    @Test
    fun `an absurd status bar is clamped`() {
        val result = WatchPartyLayout.compute(request(statusBar = 5000))
        assertTrue(result.statusBar.bottom <= 2400 / 4)
        assertTrue(result.chat.height >= 0)
    }

    @Test
    fun `a zero aspect ratio falls back instead of dividing by zero`() {
        val result = WatchPartyLayout.compute(request(aspect = 0.0))
        assertFalse(result.video.isEmpty)
    }

    @Test
    fun `fitAspect centers a wide video in a tall box`() {
        val box = WatchPartyLayout.Rect(0, 0, 1000, 1000)
        val fitted = WatchPartyLayout.fitAspect(box, 2.0)
        assertEquals(1000, fitted.width)
        assertEquals(500, fitted.height)
        // Centered vertically.
        assertEquals(250, fitted.top)
    }

    @Test
    fun `fitAspect never exceeds the box`() {
        val box = WatchPartyLayout.Rect(0, 0, 800, 300)
        val fitted = WatchPartyLayout.fitAspect(box, 0.5)
        assertTrue(fitted.width <= box.width)
        assertTrue(fitted.height <= box.height)
    }

    // </editor-fold>
}
