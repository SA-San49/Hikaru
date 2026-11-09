package eu.kanade.tachiyomi.util.system

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

object CoverColorExtractor {

    suspend fun extract(
        cover: AnimeCover,
        state: AsyncImagePainter.State.Success,
        extractColor: Boolean = true,
        updateRatio: Boolean = true,
    ) {
        if (!extractColor && !updateRatio) return

        withContext(Dispatchers.Default) {
            val image = state.result.image

            if (updateRatio) {
                // Fast ratio extraction without bitmap conversion.
                val ratio = image.width.toFloat() / image.height.toFloat()
                cover.ratio = ratio
                CoverColorObserver.updateRatio(cover.animeId, ratio)
            }

            if (!extractColor || cover.vibrantCoverColor != null || CoverColorObserver.get(cover.animeId) != null) {
                return@withContext
            }

            val context = Injekt.get<Application>()
            val originalBitmap = when (image) {
                is BitmapImage -> image.bitmap
                else -> image.asDrawable(context.resources).toBitmap()
            }

            val softwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originalBitmap.config == Bitmap.Config.HARDWARE) {
                originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                originalBitmap
            }

            val bitmap = softwareBitmap.let {
                // Downsample to a tiny size for negligible extraction cost (max 100x100).
                val scale = 100f / maxOf(it.width, it.height).coerceAtLeast(1)
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                } else {
                    it
                }
            }

            val palette = Palette.from(bitmap).generate()
            val color = palette.getVibrantColor(palette.getMutedColor(0))
            if (color != 0) {
                cover.vibrantCoverColor = color
                CoverColorObserver.update(cover.animeId, color)
            }
        }
    }
}
