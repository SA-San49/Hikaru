package eu.kanade.presentation.anime.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.ChunkedPayload
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload
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
    var progress by remember { mutableStateOf<ChunkedPayload.Progress.Partial?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val assembler = remember { ChunkedPayload.Assembler() }

    fun handleInput(raw: String, fromPaste: Boolean) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return

        // Direct single payload or complete multi-line pasted chunk set.
        val reassembled = ChunkedPayload.reassembleText(trimmed)
        if (reassembled != null && PairingPayload.decode(reassembled) != null) {
            assembler.reset()
            progress = null
            error = null
            onResult(reassembled)
            return
        }

        // Single fragment scanned from QR or pasted one part at a time.
        val chunk = ChunkedPayload.parse(trimmed)
        if (chunk != null) {
            when (val result = assembler.accept(chunk)) {
                is ChunkedPayload.Progress.Partial -> {
                    progress = result
                    error = null
                }
                is ChunkedPayload.Progress.Complete -> {
                    assembler.reset()
                    progress = null
                    if (PairingPayload.decode(result.payload) != null) {
                        error = null
                        onResult(result.payload)
                    } else {
                        error = "Those parts did not form a valid Hikaru pairing code."
                    }
                }
                is ChunkedPayload.Progress.Rejected -> {
                    error = result.reason
                }
            }
            return
        }

        error = if (fromPaste) {
            "That does not look like a complete Hikaru Watch Party code."
        } else {
            "That QR code is not a Hikaru Watch Party pairing code."
        }
    }

    AlertDialog(
        onDismissRequest = {
            assembler.reset()
            onDismissRequest()
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QrScanner(
                    onScanned = { scanned -> handleInput(scanned, fromPaste = false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                )

                progress?.let { part ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Scanned ${part.have} of ${part.total} parts — show the next code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (showPaste) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = {
                            pasted = it.trim()
                            error = null
                        },
                        label = { Text("Paste the code instead") },
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { handleInput(pasted, fromPaste = true) },
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
            TextButton(
                onClick = {
                    assembler.reset()
                    onDismissRequest()
                },
            ) {
                Text("Cancel")
            }
        },
    )
}
