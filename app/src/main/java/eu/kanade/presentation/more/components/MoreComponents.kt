package eu.kanade.presentation.more.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.LocalSettingsAccentColor

private const val SELECTED_BG_ALPHA = 0.55f
private const val ACTIVE_SWITCH_BG_ALPHA = 0.08f
private const val SUBTITLE_ALPHA = 0.78f
private const val CHEVRON_BG_ALPHA = 0.5f
private const val CHEVRON_ICON_ALPHA = 0.78f
private const val MAX_SUBTITLE_LINES = 3

private val CardInnerVerticalPadding = 4.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 14.dp
private val IconTextSpacing = 16.dp
private val TrailingSpacing = 12.dp
private val ChevronBadgeSize = 28.dp
private val ChevronIconSize = 18.dp
private val SelectedInsetPadding = 6.dp
private val SelectedVerticalPadding = 2.dp
private val TitleSize = 15.5.sp
private val SubtitleSize = 13.sp
private val SubtitleLineHeight = 17.sp

@Composable
fun MoreSection(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalSettingsAccentColor.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ExpressiveSectionHeader(
            title = title,
            accentColor = accentColor,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ExpressiveCardCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = expressiveCardBorder(),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(vertical = CardInnerVerticalPadding),
                content = content,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
fun MoreItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconTint: Color = LocalSettingsAccentColor.current,
    selected: Boolean = false,
    showDivider: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val rowModifier = if (selected) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SelectedInsetPadding, vertical = SelectedVerticalPadding)
            .clip(RoundedCornerShape(ExpressiveItemHighlightRadius))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_BG_ALPHA))
            .clickable(onClick = onClick)
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = rowModifier
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ExpressiveIconBadge(
                    icon = icon,
                    accentColor = iconTint,
                )
                Spacer(modifier = Modifier.width(IconTextSpacing))
            } else if (iconPainter != null) {
                ExpressiveIconBadge(
                    painter = iconPainter,
                    accentColor = iconTint,
                )
                Spacer(modifier = Modifier.width(IconTextSpacing))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = TitleSize,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = SubtitleSize,
                            lineHeight = SubtitleLineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SUBTITLE_ALPHA),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(TrailingSpacing))
            if (trailingContent != null) {
                trailingContent()
            } else {
                Box(
                    modifier = Modifier
                        .size(ChevronBadgeSize)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CHEVRON_BG_ALPHA),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = CHEVRON_ICON_ALPHA),
                        modifier = Modifier.size(ChevronIconSize),
                    )
                }
            }
        }
        if (showDivider) {
            val inset = if (icon != null || iconPainter != null) {
                ExpressiveDividerStartInset
            } else {
                ExpressiveDividerHorizontalInset
            }
            ExpressiveItemDivider(insetStart = inset)
        }
    }
}

@Composable
fun MoreSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalSettingsAccentColor.current,
    showDivider: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val rowBg = if (checked) {
        iconTint.copy(alpha = ACTIVE_SWITCH_BG_ALPHA)
    } else {
        Color.Transparent
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChanged(!checked)
                }
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                ExpressiveIconBadge(
                    icon = icon,
                    accentColor = iconTint,
                )
                Spacer(modifier = Modifier.width(IconTextSpacing))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = TitleSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = SubtitleSize,
                            lineHeight = SubtitleLineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SUBTITLE_ALPHA),
                        maxLines = MAX_SUBTITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(TrailingSpacing))
            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
        if (showDivider) {
            ExpressiveItemDivider(
                insetStart = if (icon != null) {
                    ExpressiveDividerStartInset
                } else {
                    ExpressiveDividerHorizontalInset
                },
            )
        }
    }
}
