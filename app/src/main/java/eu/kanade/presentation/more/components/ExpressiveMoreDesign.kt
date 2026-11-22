package eu.kanade.presentation.more.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.theme.SlateGrayAccent

private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private const val BADGE_FILL_DARK_ALPHA = 0.16f
private const val BADGE_FILL_LIGHT_ALPHA = 0.12f
private const val BADGE_BORDER_DARK_ALPHA = 0.30f
private const val BADGE_BORDER_LIGHT_ALPHA = 0.22f
private const val CARD_BORDER_ALPHA = 0.42f
private const val DIVIDER_ALPHA = 0.45f
private const val SWITCH_ANIM_DURATION_MS = 160

private const val EMERALD_DARK = 0xFF34D399
private const val EMERALD_LIGHT = 0xFF047857

internal val ExpressiveCardCornerRadius = 24.dp
internal val ExpressiveDialogCornerRadius = 28.dp
internal val ExpressiveItemHighlightRadius = 16.dp
internal val ExpressiveBadgeSize = 40.dp
internal val ExpressiveBadgeCompactSize = 36.dp
internal val ExpressiveBadgeRadius = 12.dp
internal val ExpressiveBadgeCompactRadius = 11.dp
internal val ExpressiveIconSize = 20.dp
internal val ExpressiveCompactIconSize = 18.dp
internal val ExpressiveDividerThickness = 0.75.dp
internal val ExpressiveDividerStartInset = 72.dp
internal val ExpressiveDividerHorizontalInset = 16.dp
internal val ExpressiveHeaderCapsuleWidth = 3.dp
internal val ExpressiveHeaderCapsuleHeight = 14.dp
internal val ExpressiveHeaderLetterSpacing = 0.5.sp
internal val ExpressivePillRadius = 10.dp
internal val ExpressivePillHorizontalPadding = 10.dp
internal val ExpressivePillVerticalPadding = 4.dp
internal val ExpressiveHeaderHorizontalPadding = 8.dp
internal val ExpressiveHeaderBottomPadding = 10.dp
internal val ExpressiveHeaderGap = 8.dp
internal val ExpressiveBorderWidth = 1.dp

@Immutable
data class BadgeColors(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
)

enum class CategoryAccent {
    INDIGO,
    EMERALD,
    VIOLET,
    ROSE,
    AMBER,
    CYAN,
    SLATE,
    PRIMARY,
    ;

    @Composable
    fun resolveColor(): Color = LocalSettingsAccentColor.current

    @Composable
    fun badgeColors(): BadgeColors = if (this == EMERALD) {
        emeraldStatusBadgeColors()
    } else {
        expressiveBadgeColors(resolveColor())
    }
}

@Composable
fun emeraldStatusBadgeColors(): BadgeColors {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    return expressiveBadgeColors(Color(if (isDark) EMERALD_DARK else EMERALD_LIGHT))
}

@Composable
fun expressiveBadgeColors(accentColor: Color): BadgeColors {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    val fillAlpha = if (isDark) BADGE_FILL_DARK_ALPHA else BADGE_FILL_LIGHT_ALPHA
    val borderAlpha = if (isDark) BADGE_BORDER_DARK_ALPHA else BADGE_BORDER_LIGHT_ALPHA
    return BadgeColors(
        containerColor = accentColor.copy(alpha = fillAlpha),
        contentColor = accentColor,
        borderColor = accentColor.copy(alpha = borderAlpha),
    )
}

@Composable
fun expressiveCardBorder(): BorderStroke = BorderStroke(
    width = ExpressiveBorderWidth,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA),
)

@Composable
fun ExpressiveIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalSettingsAccentColor.current,
    size: Dp = ExpressiveBadgeSize,
    cornerRadius: Dp = ExpressiveBadgeRadius,
    iconSize: Dp = ExpressiveIconSize,
    contentDescription: String? = null,
) {
    val colors = expressiveBadgeColors(accentColor)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.containerColor)
            .border(width = ExpressiveBorderWidth, color = colors.borderColor, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun ExpressiveIconBadge(
    painter: Painter,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalSettingsAccentColor.current,
    size: Dp = ExpressiveBadgeSize,
    cornerRadius: Dp = ExpressiveBadgeRadius,
    iconSize: Dp = ExpressiveIconSize,
    contentDescription: String? = null,
) {
    val colors = expressiveBadgeColors(accentColor)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.containerColor)
            .border(width = ExpressiveBorderWidth, color = colors.borderColor, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = colors.contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun ExpressiveSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalSettingsAccentColor.current,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ExpressiveHeaderHorizontalPadding, bottom = ExpressiveHeaderBottomPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ExpressiveHeaderGap),
    ) {
        Box(
            modifier = Modifier
                .width(ExpressiveHeaderCapsuleWidth)
                .height(ExpressiveHeaderCapsuleHeight)
                .clip(CircleShape)
                .background(accentColor),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = ExpressiveHeaderLetterSpacing,
            ),
            color = accentColor,
        )
    }
}

@Composable
fun ExpressiveItemDivider(
    modifier: Modifier = Modifier,
    insetStart: Dp = ExpressiveDividerStartInset,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = insetStart, end = ExpressiveDividerHorizontalInset),
        thickness = ExpressiveDividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA),
    )
}

@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val thumbIconColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = SWITCH_ANIM_DURATION_MS),
        label = "switchThumbIconColor",
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = thumbIconColor,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

@Composable
fun ExpressiveStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = SlateGrayAccent,
    contentColor: Color = Color.White,
    borderColor: Color? = null,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(ExpressivePillRadius),
        color = containerColor,
        contentColor = contentColor,
        border = borderColor?.let { BorderStroke(ExpressiveBorderWidth, it) },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ExpressivePillHorizontalPadding,
                vertical = ExpressivePillVerticalPadding,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ExpressivePillVerticalPadding),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ExpressiveHeaderCapsuleHeight),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}
