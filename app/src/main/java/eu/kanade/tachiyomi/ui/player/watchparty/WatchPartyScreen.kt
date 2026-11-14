package eu.kanade.tachiyomi.ui.player.watchparty

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.anime.watchparty.WatchPartyScannerDialog
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.ChunkPager
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.InvitationCountdown
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.P2PWatchPartySession
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingFailure
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingPayload
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingProgress
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.PairingQr
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.VerificationCodeBadge
import eu.kanade.tachiyomi.ui.player.watchparty.p2p.explanation
import tachiyomi.presentation.core.components.material.padding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watch Party sheet.
 *
 * Deliberately built from the same `PlayerSheet` + Material 3 pieces as the rest
 * of the player so it feels native. One panel hosts participants, the ready
 * check, Sync Now, chat and reactions — no extra navigation.
 */
@Composable
fun WatchPartySheet(
    state: WatchPartyUiState,
    selfId: String,
    localReady: Boolean,
    onDismissRequest: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onLeaveRoom: () -> Unit,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onCancelCountdown: () -> Unit,
    onSyncNow: () -> Unit,
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
    initialJoinCode: String? = null,
    isServerless: Boolean = false,
    pairing: P2PWatchPartySession.PairingSnapshot = P2PWatchPartySession.PairingSnapshot(),
    onCreateInvitation: () -> Unit = {},
    onAcceptAnswer: (String) -> Unit = {},
    onBeginPairing: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scanTitle by remember { mutableStateOf<String?>(null) }
    var scanHandler by remember { mutableStateOf<((String) -> Unit)?>(null) }

    PlayerSheet(onDismissRequest = onDismissRequest, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Watch Party",
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (state.active) {
                    ConnectionPill(state.connection)
                }
            }

            if (!state.active) {
                WatchPartyLobby(
                    isServerless = isServerless,
                    error = state.error,
                    initialCode = initialJoinCode,
                    onCreateRoom = onCreateRoom,
                    onJoinRoom = onJoinRoom,
                    onScan = { title, apply ->
                        scanTitle = title
                        scanHandler = apply
                    },
                )
                return@Column
            }

            RoomHeader(
                state = state,
                isServerless = isServerless,
                onLeaveRoom = onLeaveRoom,
                onSyncNow = onSyncNow,
            )

            if (isServerless) {
                ServerlessPairingSection(
                    isHost = state.isHost,
                    pairing = pairing,
                    onCreateInvitation = onCreateInvitation,
                    onAcceptAnswer = onAcceptAnswer,
                    onBeginPairing = onBeginPairing,
                    onScan = { title, apply ->
                        scanTitle = title
                        scanHandler = apply
                    },
                )
            }

            state.warning?.let { warning ->
                InfoBanner(text = warning, container = MaterialTheme.colorScheme.errorContainer)
            }
            state.error?.let { error ->
                InfoBanner(text = error, container = MaterialTheme.colorScheme.errorContainer)
            }

            CountdownBanner(
                state = state,
                onCancelCountdown = onCancelCountdown,
            )

            ReadyRow(
                state = state,
                selfId = selfId,
                localReady = localReady,
                onReady = onReady,
                onNotReady = onNotReady,
            )

            ParticipantsList(state = state, selfId = selfId)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ReactionBar(onReaction = onReaction)

            ChatSection(
                state = state,
                selfId = selfId,
                onSendChat = onSendChat,
            )
        }
    }

    val activeTitle = scanTitle
    val activeHandler = scanHandler
    if (activeTitle != null && activeHandler != null) {
        WatchPartyScannerDialog(
            title = activeTitle,
            onResult = { scanned ->
                activeHandler(scanned)
                scanTitle = null
                scanHandler = null
            },
            onDismissRequest = {
                scanTitle = null
                scanHandler = null
            },
        )
    }
}

@Composable
private fun WatchPartyLobby(
    isServerless: Boolean,
    error: String?,
    initialCode: String?,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    // A deep-linked invite pre-fills the field; the user still confirms the join.
    var code by rememberSaveable { mutableStateOf(initialCode.orEmpty()) }
    var pasteMode by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        Text(
            text = "Watch the same episode with friends. Everyone keeps their own extension, " +
                "stream and quality — only playback state, chat and reactions are shared.",
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let { InfoBanner(text = it, container = MaterialTheme.colorScheme.errorContainer) }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(MaterialTheme.padding.medium)) {
                Text("Create a room", style = MaterialTheme.typography.titleMedium)
                Text(
                    "You become the host: your playback is the reference everyone follows.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(MaterialTheme.padding.small))
                TextButton(onClick = onCreateRoom) {
                    Icon(Icons.Outlined.Group, contentDescription = null)
                    Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                    Text("Create Watch Party")
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(MaterialTheme.padding.medium)) {
                Text("Join a room", style = MaterialTheme.typography.titleMedium)
                if (isServerless) {
                    Spacer(Modifier.height(MaterialTheme.padding.small))
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
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MaterialTheme.padding.small))
                        Button(
                            onClick = { onJoinRoom(code) },
                            enabled = code.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Join with this code")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            // Accept pasted invite links and formatted codes.
                            code = input.uppercase().take(24)
                        },
                        label = { Text("Room code or invite link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val parsed = WatchPartyInviteParser.parseRoomCode(code)
                                if (parsed != null) {
                                    onJoinRoom(parsed)
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MaterialTheme.padding.small))
                    TextButton(
                        enabled = WatchPartyInviteParser.parseRoomCode(code) != null,
                        onClick = {
                            val parsed = WatchPartyInviteParser.parseRoomCode(code) ?: return@TextButton
                            onJoinRoom(parsed)
                        },
                    ) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                        Text("Join")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerlessPairingSection(
    isHost: Boolean,
    pairing: P2PWatchPartySession.PairingSnapshot,
    onCreateInvitation: () -> Unit,
    onAcceptAnswer: (String) -> Unit,
    onBeginPairing: (String) -> Unit,
    onScan: (title: String, apply: (String) -> Unit) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(pairing.codes) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2_000L)
            copied = false
        }
    }

    val role = if (isHost) PairingPayload.Role.OFFER else PairingPayload.Role.ANSWER
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pairing.failureReason?.let { PairingFailure(it) }

            when (pairing.state) {
                PairingState.CREATING_INVITATION,
                PairingState.CREATING_ANSWER,
                PairingState.VALIDATING_ANSWER,
                PairingState.ESTABLISHING,
                -> PairingProgress(pairing.state.explanation(role = role))
                else -> Unit
            }

            if (pairing.codes.isNotEmpty()) {
                Text(
                    text = pairing.state.explanation(role = role),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pairing.codes.size == 1) {
                    PairingQr(
                        payload = pairing.codes.first(),
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
                    )
                } else {
                    ChunkPager(
                        chunks = pairing.codes,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
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
                checkCode?.let {
                    VerificationCodeBadge(
                        code = it,
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
            }

            if (isHost) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCreateInvitation,
                        enabled = pairing.state != PairingState.CREATING_INVITATION,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (pairing.codes.isEmpty()) "Invite guest" else "New invite")
                    }
                    Button(
                        onClick = { onScan("Scan the guest's answer") { onAcceptAnswer(it) } },
                        enabled = pairing.codes.isNotEmpty() || pairing.state == PairingState.WAITING_FOR_GUEST,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Scan answer")
                    }
                }
            } else if (pairing.state != PairingState.CONNECTED) {
                TextButton(
                    onClick = { onScan("Scan the host's invitation") { onBeginPairing(it) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pairing.codes.isEmpty()) "Scan invitation" else "Scan another invitation")
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(connection: ConnectionState) {
    val (label, color) = when (connection) {
        ConnectionState.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.tertiary
        ConnectionState.RECONNECTING -> "Reconnecting…" to MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> "Offline" to MaterialTheme.colorScheme.error
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.18f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RoomHeader(
    state: WatchPartyUiState,
    isServerless: Boolean,
    onLeaveRoom: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (isServerless) "Direct Watch Party" else RoomCode.format(state.roomId),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (state.isHost) "You are hosting" else "Hosted by ${state.hostId?.let(state::nameOf) ?: "…"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isServerless) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(WatchPartyInviteParser.inviteText(state.roomId, state.episode)))
                            copied = true
                        },
                    ) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy invite",
                        )
                    }
                }
                IconButton(onClick = onLeaveRoom) {
                    Icon(Icons.Outlined.Close, contentDescription = "Leave room")
                }
            }
        }

        Text(
            text = state.episode?.displayName ?: "Waiting for the episode…",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.participants.size} participant${if (state.participants.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onSyncNow, enabled = state.connected && !state.isSyncing) {
                if (state.isSyncing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Sync, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (state.isSyncing) "Syncing…" else "Sync Now")
            }
        }

        if (state.isSyncing) {
            val pending = state.syncPendingIds.map(state::nameOf)
            Text(
                text = if (pending.isEmpty()) {
                    "Preparing everyone…"
                } else {
                    "Waiting for ${pending.joinToString(", ")}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CountdownBanner(
    state: WatchPartyUiState,
    onCancelCountdown: () -> Unit,
) {
    val countdown = state.countdown ?: return
    // The shared deadline drives the display: no per-device timers.
    var remaining by remember(countdown.id) { mutableStateOf(countdown.remainingSeconds(System.currentTimeMillis())) }

    LaunchedEffect(countdown.id) {
        while (true) {
            remaining = countdown.remainingSeconds(System.currentTimeMillis())
            if (remaining <= 0) break
            kotlinx.coroutines.delay(200)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Starting in", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = remaining.coerceAtLeast(0).toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(onClick = onCancelCountdown) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ReadyRow(
    state: WatchPartyUiState,
    selfId: String,
    localReady: Boolean,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
) {
    val readyCount = state.participants.count {
        state.readyStates[it.participantId] == ReadyState.READY || state.readyStates[it.participantId] == ReadyState.PLAYING
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$readyCount / ${state.participants.size} ready",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = if (localReady) onNotReady else onReady) {
                Icon(
                    if (localReady) Icons.Filled.Check else Icons.Outlined.Group,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (localReady) "I'm ready" else "Ready")
            }
        }
        if (state.participants.size < 2) {
            Text(
                "Share the room code so friends can join.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.episodeMatch != null && state.episodeMatch != EpisodeMatch.EXACT) {
            Text(
                text = when (state.episodeMatch) {
                    EpisodeMatch.PROBABLE -> "Episode matched by title only."
                    EpisodeMatch.UNRESOLVED -> "Open the room's episode to sync."
                    EpisodeMatch.MISMATCH -> "Different episode — sync is paused."
                    EpisodeMatch.EXACT -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ParticipantsList(state: WatchPartyUiState, selfId: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.participants.forEach { participant ->
            val ready = state.readyStates[participant.participantId]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Avatar(name = participant.displayName)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = participant.displayName + if (participant.participantId == selfId) " (you)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (participant.role == ParticipantRole.HOST) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) {
                                Text(
                                    "HOST",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = when (ready) {
                            ReadyState.READY -> "Ready"
                            ReadyState.PLAYING -> "Playing"
                            else -> if (participant.paused) "Paused" else "Not ready"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = formatMs(participant.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar() ?: '?'
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial.toString(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ReactionBar(onReaction: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        ReactionAggregator.QUICK_REACTIONS.forEach { emoji ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(38.dp)
                    .clickable { onReaction(emoji) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatSection(
    state: WatchPartyUiState,
    selfId: String,
    onSendChat: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.chat.size) {
        if (state.chat.isNotEmpty()) listState.animateScrollToItem(state.chat.lastIndex)
    }

    Text("Chat", style = MaterialTheme.typography.titleMedium)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.chat.isEmpty()) {
            item { Text("No messages yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.chat, key = { it.id }) { message ->
            ChatBubble(message = message, isSelf = message.senderId == selfId)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(RoomMessageCodec.MAX_CHAT_LENGTH) },
            placeholder = { Text("Message the room") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (draft.isNotBlank()) {
                        onSendChat(draft)
                        draft = ""
                    }
                },
            ),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = draft.isNotBlank(),
            onClick = {
                onSendChat(draft)
                draft = ""
            },
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, isSelf: Boolean) {
    val bubbleColor = if (isSelf) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isSelf) 14.dp else 4.dp,
                bottomEnd = if (isSelf) 4.dp else 14.dp,
            ),
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    text = if (isSelf) "You" else message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = timeFormat.format(Date(message.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String, container: Color) {
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Floating emoji overlay drawn above the video, independent of the sheet. */
@Composable
fun FloatingReactions(
    reactions: List<ReactionEvent>,
    modifier: Modifier = Modifier,
) {
    val latest = reactions.takeLast(3)
    Column(
        modifier = modifier.width(120.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        latest.forEach { reaction ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Text(
                    text = reaction.emoji + if (reaction.count > 1) " ×${reaction.count}" else "",
                    fontSize = 22.sp,
                )
            }
        }
    }
}
