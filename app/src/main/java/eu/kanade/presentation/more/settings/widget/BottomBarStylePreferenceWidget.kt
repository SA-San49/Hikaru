package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.BottomBarStyle
import tachiyomi.presentation.core.util.tvFocusHighlight

private const val CARD_ACCENT_BG_ALPHA = 0.14f
private const val CARD_SURFACE_ALPHA = 0.92f
private const val CARD_BORDER_START_ALPHA = 0.55f
private const val CARD_BORDER_MID_ALPHA = 0.25f
private const val CARD_BORDER_END_ALPHA = 0.18f
private const val EMBLEM_GLOW_ALPHA = 0.35f
private const val EMBLEM_BORDER_ALPHA = 0.75f
private const val EMBLEM_BAR_ALPHA = 0.24f
private const val EMBLEM_DOT_INACTIVE_ALPHA = 0.45f
private const val CHIP_BG_ALPHA = 0.16f
private const val ARROW_BG_ALPHA = 0.65f
private const val FOCUS_SCALE = 1.02f
private const val TOTAL_STYLES_COUNT = 8
private const val HALF_FLOAT = 0.5f
private const val BAR_WIDTH_RATIO = 0.76f
private const val BAR_HEIGHT_RATIO = 0.34f
private const val DOT_SPACING_RATIO = 0.22f
private const val DOT_RADIUS_RATIO = 0.055f

private val CardCornerRadius = 22.dp
private val CardVerticalPad = 8.dp
private val CardInnerHorizontal = 16.dp
private val CardInnerVertical = 14.dp
private val RowGap = 14.dp
private val EmblemOuterSize = 54.dp
private val EmblemInnerSize = 48.dp
private val EmblemCorner = 14.dp
private val EmblemBorderWidth = 1.5.dp
private val HeaderIconSize = 14.dp
private val TitleGap = 6.dp
private val DetailGap = 3.dp
private val ChipCorner = 6.dp
private val ChipPadH = 6.dp
private val ChipPadV = 1.dp
private val ArrowBoxSize = 28.dp
private val ArrowIconSize = 18.dp
private val GlyphSize = 32.dp

@Composable
internal fun BottomBarStylePreferenceWidget(
    value: BottomBarStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val cardShape = RoundedCornerShape(CardCornerRadius)
    val emblemShape = RoundedCornerShape(EmblemCorner)
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PrefsHorizontalPadding, vertical = CardVerticalPad)
            .tvFocusHighlight(shape = cardShape, focusedScale = FOCUS_SCALE)
            .clip(cardShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = CARD_ACCENT_BG_ALPHA),
                        surfaceContainer.copy(alpha = CARD_SURFACE_ALPHA),
                        surfaceLow,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = CARD_BORDER_START_ALPHA),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_MID_ALPHA),
                        accentColor.copy(alpha = CARD_BORDER_END_ALPHA),
                    ),
                ),
                shape = cardShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = CardInnerHorizontal, vertical = CardInnerVertical),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            Box(
                modifier = Modifier.size(EmblemOuterSize),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(EmblemOuterSize)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = EMBLEM_GLOW_ALPHA),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(EmblemInnerSize)
                        .clip(emblemShape)
                        .background(surfaceLow)
                        .border(
                            width = EmblemBorderWidth,
                            color = accentColor.copy(alpha = EMBLEM_BORDER_ALPHA),
                            shape = emblemShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(GlyphSize)) {
                        val barW = size.width * BAR_WIDTH_RATIO
                        val barH = size.height * BAR_HEIGHT_RATIO
                        val left = (size.width - barW) * HALF_FLOAT
                        val top = (size.height - barH) * HALF_FLOAT
                        val corner = barH * HALF_FLOAT
                        drawRoundRect(
                            color = accentColor.copy(alpha = EMBLEM_BAR_ALPHA),
                            topLeft = Offset(left, top),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(corner, corner),
                        )
                        val cy = size.height * HALF_FLOAT
                        val r = size.width * DOT_RADIUS_RATIO
                        val step = size.width * DOT_SPACING_RATIO
                        drawCircle(
                            color = Color.White.copy(alpha = EMBLEM_DOT_INACTIVE_ALPHA),
                            radius = r,
                            center = Offset(size.width * HALF_FLOAT - step, cy),
                        )
                        drawCircle(
                            color = accentColor,
                            radius = r * (1f + HALF_FLOAT),
                            center = Offset(size.width * HALF_FLOAT, cy),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = EMBLEM_DOT_INACTIVE_ALPHA),
                            radius = r,
                            center = Offset(size.width * HALF_FLOAT + step, cy),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DetailGap),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TitleGap),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ViewCarousel,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(HeaderIconSize),
                    )
                    Text(
                        text = "Bottom bar style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TitleGap),
                ) {
                    Text(
                        text = value.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(ChipCorner))
                            .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                            .padding(horizontal = ChipPadH, vertical = ChipPadV),
                    ) {
                        Text(
                            text = value.animationName,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(ChipPadV))
                Text(
                    text = "$TOTAL_STYLES_COUNT animated dock styles",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(ArrowBoxSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ARROW_BG_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(ArrowIconSize),
                )
            }
        }
    }
}
