// AM (CONNECTIONS) -->
package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ConnectionsLogoIcon
import eu.kanade.presentation.more.components.CategoryAccent
import eu.kanade.presentation.more.components.ExpressiveStatusPill
import eu.kanade.presentation.more.settings.LocalPreferenceHighlighted
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsService

private val ConnectionRowVerticalPadding = 12.dp

@Composable
@Suppress("ModifierNotUsedAtRoot")
fun ConnectionsPreferenceWidget(
    service: ConnectionsService,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val highlighted = LocalPreferenceHighlighted.current
    val emeraldColors = CategoryAccent.EMERALD.badgeColors()
    Box(modifier = Modifier.highlightBackground(highlighted)) {
        Row(
            modifier = modifier
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding, vertical = ConnectionRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionsLogoIcon(service)
            Text(
                text = stringResource(service.nameRes()),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = PrefsHorizontalPadding),
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = TitleFontSize,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (checked) {
                ExpressiveStatusPill(
                    text = stringResource(R.string.login_success),
                    containerColor = emeraldColors.containerColor,
                    contentColor = emeraldColors.contentColor,
                    icon = Icons.Rounded.Check,
                )
            }
        }
    }
}
// <-- AM (CONNECTIONS)
