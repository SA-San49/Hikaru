package eu.kanade.presentation.more.settings.widget

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.components.ExpressiveDialogCornerRadius
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val TRI_ROW_TINT_ALPHA = 0.55f
private val TriMessageBottomPadding = 10.dp
private val TriListSpacing = 4.dp
private val TriRowRadius = 14.dp
private val TriRowMinHeight = 48.dp
private val TriRowHorizontalPadding = 12.dp
private val TriIconEndPadding = 16.dp

private enum class State {
    CHECKED,
    INVERSED,
    UNCHECKED,
}

@SuppressLint("ComposeParameterOrder")
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "UnstableCollections")
fun <T> TriStateListDialog(
    title: String,
    message: String? = null,
    items: List<T>,
    initialChecked: List<T>,
    initialInversed: List<T>,
    itemLabel: @Composable (T) -> String,
    onDismissRequest: () -> Unit,
    onValueChanged: (newIncluded: List<T>, newExcluded: List<T>) -> Unit,
    // AM (DISCORD)>
    onlyChecked: Boolean = false,
) {
    val selected = remember {
        items
            .map {
                when (it) {
                    in initialChecked -> State.CHECKED
                    in initialInversed -> State.INVERSED
                    else -> State.UNCHECKED
                }
            }
            .toMutableStateList()
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(ExpressiveDialogCornerRadius),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        },
        text = {
            Column {
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = TriMessageBottomPadding),
                    )
                }

                Box {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(TriListSpacing),
                    ) {
                        itemsIndexed(items = items) { index, item ->
                            val state = selected[index]
                            val rowBg = when (state) {
                                State.CHECKED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = TRI_ROW_TINT_ALPHA)
                                State.INVERSED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = TRI_ROW_TINT_ALPHA)
                                State.UNCHECKED -> Color.Transparent
                            }
                            val contentTint = when (state) {
                                State.CHECKED -> MaterialTheme.colorScheme.primary
                                State.INVERSED -> MaterialTheme.colorScheme.error
                                State.UNCHECKED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(TriRowRadius))
                                    .background(rowBg)
                                    .clickable {
                                        selected[index] = when (state) {
                                            State.UNCHECKED -> State.CHECKED
                                            // AM (DISCORD)>
                                            State.CHECKED -> if (onlyChecked) State.UNCHECKED else State.INVERSED
                                            State.INVERSED -> State.UNCHECKED
                                        }
                                    }
                                    .defaultMinSize(minHeight = TriRowMinHeight)
                                    .padding(horizontal = TriRowHorizontalPadding),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    modifier = Modifier.padding(end = TriIconEndPadding),
                                    imageVector = when (state) {
                                        State.UNCHECKED -> Icons.Rounded.CheckBoxOutlineBlank
                                        State.CHECKED -> Icons.Rounded.CheckBox
                                        State.INVERSED -> Icons.Rounded.DisabledByDefault
                                    },
                                    tint = contentTint,
                                    contentDescription = stringResource(MR.strings.disabled),
                                )
                                Text(
                                    text = itemLabel(item),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (state != State.UNCHECKED) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = when (state) {
                                        State.CHECKED -> MaterialTheme.colorScheme.onPrimaryContainer
                                        State.INVERSED -> MaterialTheme.colorScheme.onErrorContainer
                                        State.UNCHECKED -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }

                    if (listState.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                    if (listState.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val included = items.mapIndexedNotNull { index, category ->
                        if (selected[index] == State.CHECKED) category else null
                    }
                    val excluded = items.mapIndexedNotNull { index, category ->
                        if (selected[index] == State.INVERSED) category else null
                    }
                    onValueChanged(included, excluded)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
