package eu.kanade.presentation.anime.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.watchparty.PairingState
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.ChunkPager
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.InvitationCountdown
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.P2PWatchPartySession
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingFailure
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingProgress
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingQr
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.VerificationCodeBadge
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.explanation

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
    lobbyError: String? = null,
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
                        error = lobbyError,
                        onCreateRoom = onCreateRoom,
                        onJoinRoom = onJoinRoom,
                        onScan = onScan,
                    )

                    else -> Room(
                        isHost = isHost,
                        isServerless = isServerless,
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
        dismissButton = if (isActive) {
            { TextButton(onClick = onDismissRequest) { Text("Keep in background") } }
        } else {
            null
        },
    )
}

@Composable
private fun Lobby(
    isServerless: Boolean,
    error: String?,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(false) }

    error?.let { PairingFailure(it) }

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
private fun ColumnScope.Room(
    isHost: Boolean,
    isServerless: Boolean,
    roomCode: String?,
    pairing: P2PWatchPartySession.PairingSnapshot,
    onCreateInvitation: () -> Unit,
    onAcceptAnswer: (String) -> Unit,
    onBeginPairing: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    if (!isServerless) {
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
    }

    if (pairing.connectedGuests > 0) {
        Text(
            text = if (isHost) {
                "${pairing.connectedGuests} guest(s) connected. Open any episode to start watching together."
            } else {
                "Connected to the host. Open the same episode to watch together."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    pairing.failureReason?.let { reason -> PairingFailure(reason) }

    val role = if (isHost) PairingPayload.Role.OFFER else PairingPayload.Role.ANSWER
    when (pairing.state) {
        PairingState.CREATING_INVITATION,
        PairingState.CREATING_ANSWER,
        PairingState.VALIDATING_ANSWER,
        PairingState.ESTABLISHING,
        -> PairingProgress(pairing.state.explanation(role = role))

        else -> Unit
    }

    if (pairing.codes.isNotEmpty()) {
        val clipboard = LocalClipboardManager.current
        var copied by remember(pairing.codes) { mutableStateOf(false) }

        LaunchedEffect(copied) {
            if (copied) {
                kotlinx.coroutines.delay(2_000L)
                copied = false
            }
        }

        Text(
            text = pairing.state.explanation(role = role),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (pairing.codes.size == 1) {
            PairingQr(
                payload = pairing.codes.first(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ChunkPager(
                chunks = pairing.codes,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "This code has ${pairing.codes.size} parts; scan each one in turn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pairing.expiresAtMs > 0L && pairing.state != PairingState.CONNECTED) {
            InvitationCountdown(
                expiresAtMs = pairing.expiresAtMs,
                nowMs = { System.currentTimeMillis() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        val checkCode = pairing.remoteVerificationCode ?: pairing.verificationCode
        checkCode?.let { code ->
            VerificationCodeBadge(
                code = code,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(pairing.codes.joinToString("\n")))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    copied -> "Copied!"
                    isHost -> "Copy invitation code"
                    else -> "Copy answer code"
                },
            )
        }
    } else if (isServerless && pairing.state == PairingState.IDLE) {
        Text(
            text = if (isHost) {
                "Tap \"Invite a guest\" to create an invitation code."
            } else {
                "Scan the host's invitation to connect."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    if (!isServerless) {
        // Relay rooms have no invitation code: the room code above *is* the
        // invitation. Offering an "Invite a guest" button here would be a button
        // that can only ever fail, which is exactly the dead end this feature
        // must not have.
        Text(
            text = if (isHost) {
                "Share the room code above. Guests who enter it join through the " +
                    "self-hosted relay."
            } else {
                "You are in this room through the relay server."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (isHost) {
        val generating = pairing.state == PairingState.CREATING_INVITATION
        OutlinedButton(
            onClick = onCreateInvitation,
            enabled = !generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (pairing.codes.isEmpty()) "Invite a guest" else "New invitation")
        }
        Button(
            onClick = { onScan("Scan the guest's answer") { onAcceptAnswer(it) } },
            enabled = pairing.codes.isNotEmpty() || pairing.state == PairingState.WAITING_FOR_GUEST,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan guest's answer")
        }
    } else if (pairing.state != PairingState.CONNECTED) {
        if (pairing.codes.isNotEmpty() || pairing.state == PairingState.CREATING_ANSWER) {
            TextButton(
                onClick = { onScan("Scan the host's invitation") { onBeginPairing(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan another invitation")
            }
        } else {
            Button(
                onClick = { onScan("Scan the host's invitation") { onBeginPairing(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan invitation")
            }
        }
    }
}
