package eu.kanade.presentation.anime.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.QrScanner

/**
 * Full scanner sheet for one pairing step.
 *
 * The scanner is a dialog rather than an inline part of the room dialog because
 * the camera needs the whole surface to focus, and because a partially scanned
 * multi-part code must survive the room dialog being redrawn every second by its
 * countdown.
 */
@Composable
fun WatchPartyScannerDialog(
    title: String,
    onResult: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // The paste field exists because a camera is not always usable: someone may be
    // reading the code off a chat message, or be on a tablet whose camera cannot
    // focus on a phone screen held close.
    var pasted by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QrScanner(
                    onScanned = onResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                )

                if (showPaste) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it.trim() },
                        label = { Text("Paste the code instead") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onResult(pasted) },
                        enabled = pasted.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use this code")
                    }
                } else {
                    TextButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Type or paste a code")
                    }
                }

                Text(
                    text = "Codes expire five minutes after they are shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
    )
}
