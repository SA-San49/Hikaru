package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactRadius
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactSize
import eu.kanade.presentation.more.components.ExpressiveCompactIconSize
import eu.kanade.presentation.more.components.ExpressiveDialogCornerRadius
import eu.kanade.presentation.more.components.ExpressiveIconBadge
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val MULTI_SELECTED_ALPHA = 0.55f
private val MultiDialogTitleGap = 12.dp
private val MultiDialogListSpacing = 4.dp
private val MultiDialogRowRadius = 14.dp
private val MultiDialogRowHorizontalPadding = 12.dp
private val MultiDialogRowVerticalPadding = 4.dp
private val MultiDialogLabelStartPadding = 12.dp

@Composable
@Suppress("UnstableCollections")
fun MultiSelectListPreferenceWidget(
    preference: Preference.PreferenceItem.MultiSelectListPreference,
    values: Set<String>,
    onValuesChange: (Set<String>) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = preference.title,
        subtitle = preference.subtitleProvider(values, preference.entries),
        icon = preference.icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val selected = remember {
            preference.entries.keys
                .filter { values.contains(it) }
                .toMutableStateList()
        }
        AlertDialog(
            onDismissRequest = { isDialogShown = false },
            shape = RoundedCornerShape(ExpressiveDialogCornerRadius),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MultiDialogTitleGap),
                ) {
                    val icon = preference.icon
                    if (icon != null) {
                        ExpressiveIconBadge(
                            icon = icon,
                            accentColor = MaterialTheme.colorScheme.primary,
                            size = ExpressiveBadgeCompactSize,
                            cornerRadius = ExpressiveBadgeCompactRadius,
                            iconSize = ExpressiveCompactIconSize,
                        )
                    }
                    Text(
                        text = preference.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            },
            text = {
                Box {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(MultiDialogListSpacing),
                    ) {
                        preference.entries.forEach { current ->
                            item {
                                val isSelected = selected.contains(current.key)
                                MultiSelectDialogRow(
                                    label = current.value,
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selected.add(current.key)
                                        } else {
                                            selected.remove(current.key)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (listState.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                    if (listState.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        onValuesChange(selected.toMutableSet())
                        isDialogShown = false
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { isDialogShown = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MultiSelectDialogRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val rowBackground = if (checked) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = MULTI_SELECTED_ALPHA)
    } else {
        Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MultiDialogRowRadius))
            .background(rowBackground)
            .clickable { onCheckedChange(!checked) }
            .minimumInteractiveComponentSize()
            .padding(
                horizontal = MultiDialogRowHorizontalPadding,
                vertical = MultiDialogRowVerticalPadding,
            ),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = MultiDialogLabelStartPadding),
        )
    }
}
