package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactRadius
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactSize
import eu.kanade.presentation.more.components.ExpressiveCompactIconSize
import eu.kanade.presentation.more.components.ExpressiveIconBadge
import eu.kanade.presentation.theme.TachiyomiPreviewTheme

private const val MAX_SUBTITLE_LINES = 10
private val SubtitleFontSize = 13.sp
private val SubtitleLineHeight = 17.sp
private val SubtitleTopPadding = 2.dp

@Composable
fun TextPreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
) {
    BasePreferenceWidget(
        modifier = modifier,
        title = title,
        subcomponent = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(
                        start = PrefsHorizontalPadding,
                        end = PrefsHorizontalPadding,
                        top = SubtitleTopPadding,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = SubtitleFontSize,
                        lineHeight = SubtitleLineHeight,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = MAX_SUBTITLE_LINES,
                )
            }
        } else {
            null
        },
        icon = if (icon != null) {
            {
                ExpressiveIconBadge(
                    icon = icon,
                    accentColor = iconTint,
                    size = ExpressiveBadgeCompactSize,
                    cornerRadius = ExpressiveBadgeCompactRadius,
                    iconSize = ExpressiveCompactIconSize,
                )
            }
        } else {
            null
        },
        onClick = onPreferenceClick,
        widget = widget,
    )
}

@PreviewLightDark
@Composable
private fun TextPreferenceWidgetPreview() {
    TachiyomiPreviewTheme {
        Surface {
            Column {
                TextPreferenceWidget(
                    title = "Text preference with icon",
                    subtitle = "Text preference summary",
                    icon = Icons.Filled.Preview,
                    onPreferenceClick = {},
                )
                TextPreferenceWidget(
                    title = "Text preference",
                    subtitle = "Text preference summary",
                    onPreferenceClick = {},
                )
            }
        }
    }
}
