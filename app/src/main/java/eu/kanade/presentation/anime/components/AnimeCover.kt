@file:Suppress("PropertyName")

package eu.kanade.presentation.anime.components

import androidx.annotation.ColorInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.presentation.core.components.SkeletonItem
import tachiyomi.domain.anime.model.AnimeCover as DomainMangaCover

val LocalAnimatedTransitions = compositionLocalOf { true }
val LocalPanoramaCover = compositionLocalOf { false }

enum class AnimeCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),

    // KMK -->
    Panorama(3f / 2f),
    // KMK <--
    ;

    enum class Size {
        Normal,
        Medium,
        Big,
    }

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.medium,
        onClick: (() -> Unit)? = null,
        // KMK -->
        alpha: Float = 1f,
        bgColor: Color? = null,
        @ColorInt tint: Int? = null,
        /** Perform action when cover loaded, specifically generating color map. If the cover doesn't update, it won't be called */
        onCoverLoaded: ((DomainMangaCover, result: AsyncImagePainter.State.Success) -> Unit)? = null,
        size: Size = Size.Normal,
        scale: ContentScale = ContentScale.Crop,
        ratio: Float = this.ratio,
        shouldExtractColor: Boolean = true,
        shouldUpdateRatio: Boolean = true,
        // KMK <--
    ) {
        val context = LocalContext.current
        val animatedTransitions = LocalAnimatedTransitions.current

        var isSuccess by remember(data) { mutableStateOf(false) }
        var isError by remember(data) { mutableStateOf(false) }
        var pendingSuccess by remember(data) { mutableStateOf<AsyncImagePainter.State.Success?>(null) }

        val needsPostProcess = shouldExtractColor || shouldUpdateRatio || onCoverLoaded != null
        LaunchedEffect(pendingSuccess, data, shouldExtractColor, shouldUpdateRatio) {
            val currentState = pendingSuccess ?: return@LaunchedEffect
            val cover = when (data) {
                is Anime -> data.asAnimeCover()
                is DomainMangaCover -> data
                else -> null
            }
            if (cover != null && (shouldExtractColor || shouldUpdateRatio)) {
                eu.kanade.tachiyomi.util.system.CoverColorExtractor.extract(
                    cover = cover,
                    state = currentState,
                    extractColor = shouldExtractColor,
                    updateRatio = shouldUpdateRatio,
                )
            }
            if (data is Anime) onCoverLoaded?.invoke(data.asAnimeCover(), currentState)
            if (data is DomainMangaCover) onCoverLoaded?.invoke(data, currentState)
        }

        Box(
            modifier = modifier
                .aspectRatio(ratio)
                .then(
                    if (shape != RectangleShape) {
                        Modifier.graphicsLayer {
                            this.shape = shape
                            clip = true
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (!isSuccess) {
                        Modifier.background(bgColor ?: CoverPlaceholderColor)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            // Static subtle placeholder while loading (avoid heavy per-card overlays when settled)
            if (animatedTransitions && !isSuccess && !isError) {
                CoverLoading(shape, bgColor)
            }

            val targetWidthPx = when {
                size == Size.Big -> null
                size == Size.Medium && ratio > RatioSwitchToPanorama -> 768
                size == Size.Medium -> 512
                ratio > RatioSwitchToPanorama -> 540
                else -> 360
            }
            val targetHeightPx = when {
                size == Size.Big -> null
                size == Size.Medium && ratio > RatioSwitchToPanorama -> 512
                size == Size.Medium -> 768
                ratio > RatioSwitchToPanorama -> 360
                else -> 540
            }

            AsyncImage(
                model = remember(data, animatedTransitions, targetWidthPx, targetHeightPx) {
                    ImageRequest.Builder(context)
                        .data(data)
                        .apply {
                            if (targetWidthPx != null && targetHeightPx != null) {
                                size(targetWidthPx, targetHeightPx)
                            }
                        }
                        .precision(coil3.size.Precision.INEXACT)
                        .crossfade(animatedTransitions)
                        .build()
                },
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (alpha < 1f) {
                            Modifier.graphicsLayer { this.alpha = alpha }
                        } else {
                            Modifier
                        },
                    ),
                contentScale = scale,
                onSuccess = { result ->
                    if (!isSuccess) isSuccess = true
                    if (isError) isError = false
                    if (needsPostProcess) {
                        pendingSuccess = result
                    }
                },
                onError = {
                    if (isSuccess) isSuccess = false
                    if (!isError) isError = true
                },
            )

            if (isError) {
                CoverError(size, tint, contentDescription)
            }
        }
    }

    companion object {
        val COVER_TEMPLATE_SIZE_BIG = 16.dp
        val COVER_TEMPLATE_SIZE_MEDIUM = 24.dp
        val COVER_TEMPLATE_SIZE_NORMAL = 32.dp

        @Composable
        private fun BoxScope.CoverLoading(shape: Shape, bgColor: Color?) {
            SkeletonItem(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = (bgColor ?: CoverPlaceholderColor).copy(alpha = 0.5f),
            )
        }

        @Composable
        private fun BoxScope.CoverError(size: Size, tint: Int?, contentDescription: String) {
            androidx.compose.foundation.Image(
                imageVector = ImageVector.vectorResource(R.drawable.cover_error_vector),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(
                        when (size) {
                            Size.Big -> COVER_TEMPLATE_SIZE_BIG
                            Size.Medium -> COVER_TEMPLATE_SIZE_MEDIUM
                            else -> COVER_TEMPLATE_SIZE_NORMAL
                        },
                    )
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(
                    tint?.let { Color(it) } ?: CoverPlaceholderOnBgColor,
                ),
            )
        }

        @Composable
        fun getRatio(animeId: Long): Float {
            val usePanorama = LocalPanoramaCover.current

            if (!usePanorama) return Book.ratio

            val ratio by androidx.compose.runtime.produceState(
                initialValue = CoverColorObserver.ratios.value[animeId] ?: Book.ratio,
                animeId,
            ) {
                CoverColorObserver.ratios
                    .map { it[animeId] ?: Book.ratio }
                    .distinctUntilChanged()
                    .collect { value = it }
            }
            return ratio
        }

        @Composable
        fun getEntry(animeId: Long, usePanoramaOverride: Boolean? = null): Pair<AnimeCover, Float> {
            val globalUsePanorama = LocalPanoramaCover.current
            val usePanorama = usePanoramaOverride ?: globalUsePanorama

            if (!usePanorama) return Book to Book.ratio

            val ratio by androidx.compose.runtime.produceState(
                initialValue = CoverColorObserver.ratios.value[animeId] ?: Book.ratio,
                animeId,
            ) {
                CoverColorObserver.ratios
                    .map { it[animeId] ?: Book.ratio }
                    .distinctUntilChanged()
                    .collect { value = it }
            }

            return remember(ratio) {
                val entry = if (ratio > RatioSwitchToPanorama) Panorama else Book
                entry to ratio
            }
        }
    }
}

enum class AnimeCoverHide(private val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),
    ;

    @Composable
    operator fun invoke(
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.medium,
        onClick: (() -> Unit)? = null,
        // KMK -->
        /** background color, which used for loading/error indicator */
        bgColor: Color? = CoverPlaceholderColor,
        /** onBackground color, which used for loading/error indicator */
        @ColorInt tint: Int? = null,
    ) {
        val modifierColored = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(bgColor ?: CoverPlaceholderColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )

        Box(
            modifier = modifierColored,
        ) {
            androidx.compose.foundation.Image(
                imageVector = ImageVector.vectorResource(R.drawable.ic_baseline_menu_book_24),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(
                    tint?.let { Color(it) } ?: CoverPlaceholderOnBgColor,
                ),
            )
        }
    }
}

internal const val RatioSwitchToPanorama = 1.1f

internal val CoverPlaceholderColor = Color(0x1F888888)
internal val CoverPlaceholderOnBgColor = Color(0x8F888888)
