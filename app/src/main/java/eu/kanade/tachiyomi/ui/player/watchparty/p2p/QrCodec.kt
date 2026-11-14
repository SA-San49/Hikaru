package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * All ZXing contact lives here.
 *
 * Two reasons to isolate it:
 *
 *  - **One place to reason about untrusted input.** Everything else in pairing
 *    deals with strings that came from this file, so it is the only component
 *    that has to be defensive about camera frames and user-supplied images.
 *  - **The library is only needed here.** ZXing's `core` artifact is small, but
 *    keeping the API surface to two functions makes it obvious how much of our
 *    behaviour depends on it.
 *
 * Decoding returns `null` rather than throwing for every failure mode: a camera
 * sees motion blur, glare, fingers and unrelated barcodes continuously, and none
 * of those are errors.
 */
object QrCodec {

    /**
     * Quiet zone around the modules.
     *
     * The QR spec requires four; scanners in practice work with two, and every
     * saved pixel of margin is a pixel of payload. Four is kept anyway: a code
     * that fails to scan is worse than one that is one row smaller.
     */
    private const val QUIET_ZONE_MODULES = 4

    /**
     * Renders [content] as a square QR bitmap of roughly [sizePx] pixels.
     *
     * Uses high error correction because these codes are shown on a phone screen
     * that may be greasy, dimmed, or photographed at an angle. It costs some
     * capacity, which [ChunkedPayload] exists to absorb.
     */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isBlank()) return null
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = runCatching {
            MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        }.getOrNull() ?: return null

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) black else white
            }
        }
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /**
     * Decodes a QR code from a camera frame.
     *
     * [luminance] is the Y plane of a `YUV_420_888` image, which is what
     * CameraX's `ImageProxy` exposes. Working on the Y plane directly avoids a
     * full RGB conversion per frame, which matters on the low-end devices this
     * feature most needs to support.
     *
     * @param data the Y plane bytes.
     * @param width frame width in pixels.
     * @param height frame height in pixels.
     * @param rowStride bytes per row, which may exceed [width]; callers get it
     *   from `ImageProxy.getPlanes()[0].getRowStride()`.
     */
    fun decodeFrame(data: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (data.isEmpty() || width <= 0 || height <= 0) return null
        if (rowStride < width) return null
        // A frame this small cannot contain a scannable code, and feeding it to
        // the decoder just burns CPU every frame.
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) return null

        return runCatching {
            val source = PlanarYUVLuminanceSource(
                data,
                rowStride,
                height,
                /* left = */ 0,
                /* top = */ 0,
                width,
                height,
                /* reverseHorizontal = */ false,
            )
            readText(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()
    }

    /**
     * Decodes a QR code from a still image the user picked from the gallery.
     *
     * This is the fallback for a cracked camera, a camera that cannot focus on
     * another phone's screen, or a screenshot of an invitation that was sent
     * through a chat app.
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) return null
        return runCatching {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            readText(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))
        }.getOrNull()
    }

    /**
     * Runs the reader with the formats Hikaru cares about.
     *
     * Only QR: a Code-128 barcode has no payload Hikaru can use, and trying every
     * format on every frame roughly triples the per-frame cost.
     */
    private fun readText(bitmap: BinaryBitmap): String? {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull() ?: run {
            reader.reset()
            return null
        }
        reader.reset()
        return result.text?.takeIf { it.isNotBlank() }
    }

    /** Below this a QR code cannot physically be represented. */
    private const val MIN_DIMENSION = 48
}
