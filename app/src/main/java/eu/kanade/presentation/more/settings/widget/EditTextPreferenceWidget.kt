package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactRadius
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactSize
import eu.kanade.presentation.more.components.ExpressiveCompactIconSize
import eu.kanade.presentation.more.components.ExpressiveDialogCornerRadius
import eu.kanade.presentation.more.components.ExpressiveIconBadge
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val EditDialogTitleGap = 12.dp
private val EditDialogSubtitleTopPadding = 6.dp
private val EditDialogFieldRadius = 14.dp

@Composable
fun EditTextPreferenceWidget(
    title: String,
    subtitle: String?,
    dialogSubtitle: String? = null,
    icon: ImageVector?,
    value: String,
    onConfirm: suspend (String) -> Boolean,
    singleLine: Boolean = true,
    canBeBlank: Boolean = false,
    formatSubtitle: Boolean = true,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = if (formatSubtitle) subtitle?.format(value) else subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val scope = rememberCoroutineScope()
        val onDismissRequest = { isDialogShown = false }
        var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(value))
        }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            shape = RoundedCornerShape(ExpressiveDialogCornerRadius),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EditDialogTitleGap),
                    ) {
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
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    if (dialogSubtitle != null) {
                        Text(
                            text = dialogSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = EditDialogSubtitleTopPadding),
                        )
                    }
                }
            },
            text = {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    shape = RoundedCornerShape(EditDialogFieldRadius),
                    trailingIcon = {
                        if (textFieldValue.text.isBlank() && !canBeBlank) {
                            Icon(imageVector = Icons.Filled.Error, contentDescription = null)
                        } else {
                            IconButton(onClick = { textFieldValue = TextFieldValue("") }) {
                                Icon(imageVector = Icons.Filled.Cancel, contentDescription = null)
                            }
                        }
                    },
                    isError = textFieldValue.text.isBlank() && !canBeBlank,
                    singleLine = singleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmButton = {
                FilledTonalButton(
                    enabled = textFieldValue.text != value && (textFieldValue.text.isNotBlank() || canBeBlank),
                    onClick = {
                        scope.launch {
                            if (onConfirm(textFieldValue.text)) {
                                onDismissRequest()
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
