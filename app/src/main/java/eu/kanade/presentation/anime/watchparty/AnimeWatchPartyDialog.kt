package eu.kanade.presentation.anime.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingFailure
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.QrCodec
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingProgress
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingQr
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.VerificationCodeBadge
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.explanation
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.P2PWatchPartySession

/**
 * The Watch Party entry point on the anime detail page.
 *
 * Deliberately a dialog rather than a screen: creating a room must not require
 * opening an episode first. The user picks the anime, invites people, and only
 * then presses play — which is the order that matches how people actually watch
 * together.
 */
@Composable
fun AnimeWatchPartyDialog(
    animeTitle: String,
    isEnabled: Boolean,
    isActive: Boolean,
    isHost: Boolean,
    roomCode: String?,
    pairing: P2PWatchPartySession.PairingSnapshot,
    isServerless: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onLeaveRoom: () -> Unit,
    onCreateInvitation: () -> Unit,
    onAcceptAnswer: (String) -> Unit,
    onBeginPairing: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
        title = { Text("Watch Party") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = animeTitle, style = MaterialTheme.typography.bodyMedium)

                when {
                    !isEnabled -> Text(
                        text = "Watch Party is turned off in Settings → Player.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    !isActive -> Lobby(
                        isServerless = isServerless,
                        onCreateRoom = onCreateRoom,
                        onJoinRoom = onJoinRoom,
                        onScan = onScan,
                    )

                    else -> Room(
                        isHost = isHost,
                        roomCode = roomCode,
                        pairing = pairing,
                        onCreateInvitation = onCreateInvitation,
                        onAcceptAnswer = onAcceptAnswer,
                        onBeginPairing = onBeginPairing,
                        onScan = onScan,
                    )
                }
            }
        },
        confirmButton = {
            if (isActive) {
                TextButton(onClick = onLeaveRoom) { Text("Leave room") }
            } else {
                TextButton(onClick = onDismissRequest) { Text("Close") }
            }
        },
    )
}

@Composable
private fun Lobby(
    isServerless: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(false) }

    OutlinedButton(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth()) {
        Text("Create a room")
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    if (isServerless) {
        // Serverless joining is not "type a room code": there is no server to ask
        // who is in that room, and no offer to answer. A guest needs the host's
        // invitation itself, which carries the ICE credentials, the fingerprint
        // and the room id.
        Text(text = "or join a room", style = MaterialTheme.typography.labelLarge)

        Button(
            onClick = { onScan("Scan the host's invitation") { onJoinRoom(it) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan invitation")
        }

        TextButton(
            onClick = { pasteMode = !pasteMode },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (pasteMode) "Hide paste box" else "Paste invitation instead")
        }

        if (pasteMode) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Invitation code") },
                placeholder = { Text("HP1…") },
                singleLine = false,
                maxLines = 4,
                // No capitalisation: the payload is base64url and case matters.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onJoinRoom(code) },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Join with this code")
            }
            Text(
                text = "Ask the host to send you their invitation, or use Copy invitation " +
                    "code on their device and paste it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(text = "or join a room", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(12) },
            label = { Text("Room code") },
            singleLine = true,
            // Relay room codes are uppercase alphanumerics; forcing capitals
            // avoids the most common cause of a failed join.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onJoinRoom(code) },
            enabled = code.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Join")
        }
        Text(
            text = "This room uses the self-hosted relay configured in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Room(
    isHost: Boolean,
    roomCode: String?,
    pairing: P2PWatchPartySession.PairingSnapshot,
    onCreateInvitation: () -> Unit,
    onAcceptAnswer: (String) -> Unit,
    onBeginPairing: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    roomCode?.let {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Room code", style = MaterialTheme.typography.labelMedium)
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (pairing.state == PairingState.CONNECTED) {
        Text(
            text = if (isHost) {
                "${pairing.connectedGuests} guest(s) connected."
            } else {
                "Connected to the host."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    pairing.failureReason?.let { reason -> PairingFailure(reason) }

    when (pairing.state) {
        PairingState.CREATING_INVITATION,
        PairingState.CREATING_ANSWER,
        PairingState.VALIDATING_ANSWER,
        PairingState.ESTABLISHING,
        -> PairingProgress(pairing.state.explanation(role = if (isHost) {
            eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload.Role.OFFER
        } else {
            eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload.Role.ANSWER
        }))

        else -> Unit
    }

    if (pairing.codes.isNotEmpty()) {
        val primary = pairing.codes.first()
        // A QR that fails to render must not make pairing impossible: the same
        // string can be sent through any chat app. The bitmap is nullable for
        // exactly this case.
        val rendered = remember(primary) { QrCodec.encode(primary, 720) }
        if (rendered != null) {
            PairingQr(payload = primary)
        } else {
            Text(
                text = "Could not draw the code on this device. Copy it instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (pairing.codes.size > 1) {
            Text(
                text = "This invitation has ${pairing.codes.size} parts; send each in turn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pairing.verificationCode?.let { code -> VerificationCodeBadge(code) }

        // The paste fallback, always available. This is the difference between a
        // broken camera (or a broken QR renderer) being a dead end and being a
        // minor inconvenience.
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        TextButton(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(primary))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy invitation code")
        }
    } else if (pairing.state == PairingState.IDLE) {
        Text(
            text = "Tap \"Invite a guest\" to create an invitation code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    if (isHost) {
        OutlinedButton(onClick = onCreateInvitation, modifier = Modifier.fillMaxWidth()) {
            Text(if (pairing.codes.isEmpty()) "Invite a guest" else "New invitation")
        }
        OutlinedButton(
            onClick = { onScan("Scan the guest's answer") { onAcceptAnswer(it) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan guest's answer")
        }
    } else {
        OutlinedButton(
            onClick = { onScan("Scan the host's invitation") { onBeginPairing(it) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan invitation")
        }
    }
}
