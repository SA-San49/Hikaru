package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Geometry for the Watch Party player screen.
 *
 * ## The problem this solves
 *
 * The normal player gives the video surface and the controls layer the whole
 * screen and lets mpv letterbox inside it. That is fine when the only overlay is
 * a few buttons, but with a chat panel underneath it produces the worst of both
 * worlds: the video is centred in a box that is taller than it needs to be, so
 * there are black bars above *and* below, and the chat is squeezed into what is
 * left.
 *
 * Watch Party instead gives the video a box sized to its own aspect ratio and
 * hands the remaining height to chat. The video keeps its exact shape (no
 * cropping, no stretching) and the dead space *becomes* the chat.
 *
 * ## Why this is a pure function
 *
 * Getting the split wrong is subtle: subtract the status bar twice, forget the
 * input row, or round up one pixel and the video gets clipped at the bottom. This
 * is arithmetic that can be verified on the JVM for every screen size and aspect
 * ratio, so it is written as arithmetic and tested as arithmetic.
 */
object WatchPartyLayout {

    /** How the video and chat are stacked. */
    enum class Mode {
        /** Portrait or a square-ish window: video on top, chat below. */
        STACKED,

        /** Landscape: video beside a narrow chat column. */
        SIDE_BY_SIDE,
    }

    /**
     * The layout decision for a window.
     *
     * Landscape phones have almost no vertical room, so stacking would leave the
     * video a sliver. Wide windows therefore put chat beside the video, which
     * also keeps the video about as large as the ordinary player would.
     */
    fun modeFor(widthPx: Int, heightPx: Int, minSideBySideWidthDp: Int = 600): Mode {
        if (widthPx <= 0 || heightPx <= 0) return Mode.STACKED
        return if (widthPx > heightPx) Mode.SIDE_BY_SIDE else Mode.STACKED
    }

    /** Pixel rectangles for the three regions of the screen. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /** Everything the layout needs to know about the window. */
    data class Request(
        val widthPx: Int,
        val heightPx: Int,
        /** System status bar height, already in pixels. */
        val statusBarPx: Int,
        /** IME height when the keyboard is open; `0` when closed. */
        val imePx: Int,
        /** Height of the chat input row. */
        val inputRowPx: Int,
        /** Video aspect ratio as width / height; `16.0/9.0` for HD. */
        val videoAspect: Double,
        /** Chat column width in side-by-side mode. */
        val sideChatWidthPx: Int,
        val mode: Mode,
    )

    /** The regions to draw. */
    data class Result(
        val video: Rect,
        val chat: Rect,
        val input: Rect,
        val statusBar: Rect,
        val mode: Mode,
    )

    /**
     * Splits the window into video, chat and input regions.
     *
     * Guarantees, in order of importance:
     *
     *  1. **The video keeps its aspect ratio exactly.** It is sized to fit, never
     *     cropped and never stretched, so nothing is lost at the edges.
     *  2. **The input never disappears.** When the keyboard opens, the chat
     *     shrinks and the input stays directly above the IME — a chat you cannot
     *     type into is not a chat.
     *  3. **Nothing overlaps.** Every region is disjoint, and together they cover
     *     the window with no dead space beyond the video's own letterbox.
     */
    fun compute(request: Request): Result {
        if (request.widthPx <= 0 || request.heightPx <= 0) {
            val empty = Rect(0, 0, 0, 0)
            return Result(empty, empty, empty, empty, request.mode)
        }

        val width = request.widthPx
        val height = request.heightPx
        val statusBar = request.statusBarPx.coerceIn(0, height / 4)
        // The IME sits at the bottom of the window and shrinks everything above it.
        val ime = request.imePx.coerceIn(0, height - statusBar)
        val bottom = height - ime
        val contentTop = statusBar
        val contentHeight = (bottom - contentTop).coerceAtLeast(0)

        return when (request.mode) {
            Mode.SIDE_BY_SIDE -> sideBySide(request, statusBar, contentTop, contentHeight, bottom, width)
            Mode.STACKED -> stacked(request, statusBar, contentTop, contentHeight, bottom, width)
        }
    }

    private fun sideBySide(
        request: Request,
        statusBar: Int,
        contentTop: Int,
        contentHeight: Int,
        bottom: Int,
        width: Int,
    ): Result {
        val statusRect = Rect(0, 0, width, statusBar)
        // The chat column is capped so the video keeps the majority of the width.
        val chatWidth = request.sideChatWidthPx.coerceIn(0, (width * 3) / 5)
        val videoWidth = (width - chatWidth).coerceAtLeast(0)
        val inputHeight = request.inputRowPx.coerceIn(0, contentHeight)
        val chatTop = contentTop
        val chatBottom = (bottom - inputHeight).coerceAtLeast(chatTop)

        // Fit the video inside the available column without distorting it.
        val available = Rect(0, contentTop, videoWidth, bottom)
        val fitted = fitAspect(available, request.videoAspect)

        return Result(
            video = fitted,
            chat = Rect(videoWidth, chatTop, width, chatBottom),
            input = Rect(videoWidth, chatBottom, width, bottom),
            statusBar = statusRect,
            mode = Mode.SIDE_BY_SIDE,
        )
    }

    private fun stacked(
        request: Request,
        statusBar: Int,
        contentTop: Int,
        contentHeight: Int,
        bottom: Int,
        width: Int,
    ): Result {
        val statusRect = Rect(0, 0, width, statusBar)
        val inputHeight = request.inputRowPx.coerceIn(0, contentHeight)
        val available = Rect(0, contentTop, width, bottom)

        // Natural video height for this width, capped so chat always gets room.
        val naturalHeight = if (request.videoAspect > 0.0) {
            (width / request.videoAspect).toInt()
        } else {
            contentHeight / 2
        }
        // At least a third of the content stays for chat; a video that eats the
        // whole screen is the bug this layout exists to fix.
        val maxVideoHeight = (contentHeight * 2) / 3
        val videoHeight = naturalHeight.coerceIn(0, maxVideoHeight.coerceAtLeast(0))
        val videoBox = Rect(0, contentTop, width, contentTop + videoHeight)
        val fitted = fitAspect(videoBox, request.videoAspect, centerVertically = false)

        val chatTop = fitted.bottom
        val chatBottom = (bottom - inputHeight).coerceAtLeast(chatTop)
        return Result(
            video = fitted,
            chat = Rect(0, chatTop, width, chatBottom),
            input = Rect(0, chatBottom, width, bottom),
            statusBar = statusRect,
            mode = Mode.STACKED,
        )
    }

    /**
     * Centers the largest rectangle of [aspect] that fits inside [box].
     *
     * Video keeps its shape: the result is never wider or taller than the box, so
     * it cannot overflow into the chat.
     */
    fun fitAspect(box: Rect, aspect: Double, centerVertically: Boolean = true): Rect {
        if (box.isEmpty) return box
        if (aspect <= 0.0) return box

        val boxAspect = box.width.toDouble() / box.height
        return if (boxAspect > aspect) {
            // Box is wider than the video, so height is the limit. The video fills
            // the box's height and is centred horizontally.
            val fittedWidth = (box.height * aspect).toInt().coerceAtMost(box.width)
            val inset = (box.width - fittedWidth) / 2
            Rect(box.left + inset, box.top, box.left + inset + fittedWidth, box.bottom)
        } else {
            // Box is taller than the video, so width is the limit. The video fills
            // the box's width and hangs from the top by default: it sits directly
            // under the status bar, with the slack above the chat rather than
            // floating in the middle of the screen.
            val fittedHeight = (box.width / aspect).toInt().coerceAtMost(box.height)
            val inset = if (centerVertically) (box.height - fittedHeight) / 2 else 0
            Rect(box.left, box.top + inset, box.right, box.top + inset + fittedHeight)
        }
    }

    /** True when the video is being shown without any wasted space. */
    fun isTight(result: Result, aspect: Double): Boolean {
        if (result.video.isEmpty || aspect <= 0.0) return false
        val actual = result.video.width.toDouble() / result.video.height
        return kotlin.math.abs(actual - aspect) < 0.02
    }
}
