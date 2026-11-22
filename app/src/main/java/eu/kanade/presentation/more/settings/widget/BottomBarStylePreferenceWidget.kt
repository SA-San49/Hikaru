package eu.kanade.presentation.more.settings.widget

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
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.BottomBarStyle
import eu.kanade.presentation.home.StyledDockItem
import eu.kanade.presentation.home.StyledDockPreviewBar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.util.tvFocusHighlight

private const val CARD_ACCENT_BG_ALPHA = 0.14f
private const val CARD_SURFACE_ALPHA = 0.92f
private const val CARD_BORDER_START_ALPHA = 0.55f
private const val CARD_BORDER_MID_ALPHA = 0.25f
private const val CARD_BORDER_END_ALPHA = 0.18f
private const val CHIP_BG_ALPHA = 0.16f
private const val ARROW_BG_ALPHA = 0.65f
private const val FOCUS_SCALE = 1.02f
private const val SAMPLE_ACTIVE_TAB_INDEX = 2

private val CardCornerRadius = 22.dp
private val CardVerticalPad = 8.dp
private val CardInnerHorizontal = 16.dp
private val CardInnerVertical = 14.dp
private val HeaderIconBoxSize = 36.dp
private val HeaderIconSize = 18.dp
private val HeaderGap = 12.dp
private val TitleGap = 6.dp
private val ChipCorner = 6.dp
private val ChipPadH = 6.dp
private val ChipPadV = 2.dp
private val AnimIconSize = 13.dp
private val ArrowBoxSize = 28.dp
private val ArrowIconSize = 18.dp
private val PreviewStripTopGap = 12.dp
private val PreviewIconSize = 16.dp

@Composable
internal fun BottomBarStylePreferenceWidget(
    value: BottomBarStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val cardShape = RoundedCornerShape(CardCornerRadius)
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLowest

    val sampleTabs: ImmutableList<StyledDockItem> = remember(onClick) {
        persistentListOf(
            StyledDockItem(
                id = "library",
                title = "Library",
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = Icons.Outlined.CollectionsBookmark,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            ),
            StyledDockItem(
                id = "history",
                title = "History",
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            ),
            StyledDockItem(
                id = "schedule",
                title = "Schedule",
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            ),
            StyledDockItem(
                id = "browse",
                title = "Browse",
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = Icons.Outlined.Explore,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            ),
            StyledDockItem(
                id = "more",
                title = "More",
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            ),
        )
    }

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
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HeaderGap),
            ) {
                Box(
                    modifier = Modifier
                        .size(HeaderIconBoxSize)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = CHIP_BG_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ViewCarousel,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(HeaderIconSize),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ChipPadV),
                ) {
                    Text(
                        text = "Bottom navigation bar style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(ChipCorner))
                                .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                                .padding(horizontal = ChipPadH, vertical = ChipPadV),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ChipPadV),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Animation,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(AnimIconSize),
                            )
                            Text(
                                text = value.animationName,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                maxLines = 1,
                            )
                        }
                    }
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

            Spacer(modifier = Modifier.height(PreviewStripTopGap))

            StyledDockPreviewBar(
                style = value,
                items = sampleTabs,
                selectedIndex = SAMPLE_ACTIVE_TAB_INDEX,
                compact = true,
            )
        }
    }
}
