package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.more.components.ExpressiveBorderWidth
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val INFO_CONTAINER_ALPHA = 0.45f
private const val INFO_BORDER_ALPHA = 0.35f
private const val INFO_BADGE_ALPHA = 0.16f
private val InfoCardRadius = 18.dp
private val InfoCardVerticalMargin = 8.dp
private val InfoInnerPadding = 14.dp
private val InfoRowGap = 12.dp
private val InfoBadgeSize = 32.dp
private val InfoIconSize = 18.dp
private val InfoLineHeight = 18.sp

@Composable
internal fun InfoWidget(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PrefsHorizontalPadding,
                vertical = InfoCardVerticalMargin,
            ),
        shape = RoundedCornerShape(InfoCardRadius),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = INFO_CONTAINER_ALPHA),
        border = BorderStroke(
            width = ExpressiveBorderWidth,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = INFO_BORDER_ALPHA),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(InfoInnerPadding),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(InfoRowGap),
        ) {
            Box(
                modifier = Modifier
                    .size(InfoBadgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = INFO_BADGE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(InfoIconSize),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = InfoLineHeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun InfoWidgetPreview() {
    TachiyomiPreviewTheme {
        Surface {
            InfoWidget(text = stringResource(MR.strings.download_ahead_info))
        }
    }
}
