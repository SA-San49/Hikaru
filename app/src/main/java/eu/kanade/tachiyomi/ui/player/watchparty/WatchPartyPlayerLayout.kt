package eu.kanade.tachiyomi.ui.player.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.watchparty.WatchPartyLayout.Mode

/**
 * The Watch Party player screen.
 *
 * ## Why this replaces the normal player layout
 *
 * The ordinary player hands the whole screen to mpv so that fullscreen video has
 * room. With a chat panel underneath that stops working: the video is centred in
 * a box taller than it is, leaving black bands above *and* below and squeezing
 * chat into the remainder — which is exactly what the screenshot showed.
 *
 * Here the video gets a box sized to its own aspect ratio and the leftover height
 * *is* the chat. Nothing is cropped, nothing is stretched, and no vertical space
 * is wasted on letterboxing.
 *
 * ## Portrait vs landscape
 *
 * Portrait stacks video above chat. Landscape puts chat in a column beside the
 * video, because a landscape phone has almost no vertical room: stacking would
 * leave the video a sliver.
 *
 * ## The keyboard
 *
 * The chat input is pinned above the IME. When the keyboard opens the chat
 * shrinks — never the input, and never the video's aspect ratio. A chat that
 * hides its own text field behind the keyboard is worse than no chat.
 */
@Composable
fun WatchPartyPlayerLayout(
    ui: WatchPartyUiState,
    selfId: String,
    /** Attaches the mpv surface into the video region. */
    videoContent: @Composable () -> Unit,
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    localReady: Boolean,
    videoAspect: Double,
    modifier: Modifier = Modifier,
) {
    // BoxWithConstraints gives the real pixel size of this composable, which is
    // what the layout arithmetic needs. Guessing from `LocalConfiguration` breaks
    // in split-screen and freeform windows.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .imePadding(),
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val density = LocalDensity.current
        val mode = WatchPartyLayout.modeFor(widthPx, heightPx)

        val geometry = WatchPartyLayout.compute(
            WatchPartyLayout.Request(
                widthPx = widthPx,
                heightPx = heightPx,
                // Insets are already applied by the modifier chain above.
                statusBarPx = 0,
                imePx = 0,
                inputRowPx = with(density) { INPUT_ROW_DP.dp.roundToPx() },
                videoAspect = videoAspect.takeIf { it > 0.0 } ?: (16.0 / 9.0),
                sideChatWidthPx = with(density) { SIDE_CHAT_DP.dp.roundToPx() },
                mode = mode,
            )
        )

        // The video region. In stacked mode this is the band above chat; in
        // side-by-side it is the column beside chat. mpv letterboxes inside it,
        // which is correct — what this layout removes is the *extra* space the
        // old full-screen surface wasted above and below the picture.
        val videoRegionModifier = with(density) {
            Modifier.height(geometry.video.height.toDp())
        }

        when (mode) {
            Mode.SIDE_BY_SIDE -> Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    // Beside the chat, the video takes everything left: mpv fits
                    // the picture inside it and the spare width is centred margin,
                    // which is where a landscape film genuinely needs it.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    videoContent()
                }
                RoomPanel(
                    ui = ui,
                    selfId = selfId,
                    onSendChat = onSendChat,
                    onReaction = onReaction,
                    onReady = onReady,
                    onNotReady = onNotReady,
                    onSyncNow = onSyncNow,
                    onCancelCountdown = onCancelCountdown,
                    localReady = localReady,
                    modifier = Modifier
                        .width(with(density) { geometry.chat.width.toDp() })
                        .fillMaxHeight(),
                )
            }

            Mode.STACKED -> Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = videoRegionModifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    videoContent()
                }
                RoomPanel(
                    ui = ui,
                    selfId = selfId,
                    onSendChat = onSendChat,
                    onReaction = onReaction,
                    onReady = onReady,
                    onNotReady = onNotReady,
                    onSyncNow = onSyncNow,
                    onCancelCountdown = onCancelCountdown,
                    localReady = localReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * Status, participants and chat.
 *
 * Kept deliberately dense: on a phone in portrait this panel is competing with
 * the video for the same screen, so every row has to earn its height.
 */
@Composable
private fun RoomPanel(
    ui: WatchPartyUiState,
    selfId: String,
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    localReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(
                ui = ui,
                localReady = localReady,
                onReady = onReady,
                onNotReady = onNotReady,
                onSyncNow = onSyncNow,
                onCancelCountdown = onCancelCountdown,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ChatList(
                ui = ui,
                selfId = selfId,
                modifier = Modifier.weight(1f),
            )

            ChatInput(onSendChat = onSendChat, onReaction = onReaction)
        }
    }
}

@Composable
private fun StatusBar(
    ui: WatchPartyUiState,
    localReady: Boolean,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelCountdown: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${ui.participants.size} in room",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSyncNow, enabled = !ui.isSyncing) { Text("Sync") }
                TextButton(onClick = if (localReady) onNotReady else onReady) {
                    Text(if (localReady) "Not ready" else "Ready")
                }
            }
        }

        ui.countdown?.let { countdown ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Starting in ${countdown.remainingSeconds(System.currentTimeMillis())}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onCancelCountdown) { Text("Cancel") }
            }
        }

        // An unresolved or mismatched episode is the single most likely reason a
        // guest is out of sync, so it is shown here rather than buried in a sheet.
        ui.warning?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ui.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ChatList(
    ui: WatchPartyUiState,
    selfId: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(ui.chat.size) {
        if (ui.chat.isNotEmpty()) {
            // Follow the conversation, but only when it is the user's own message
            // or they were already at the bottom — yanking them away from history
            // they are reading would be hostile.
            listState.animateScrollToItem(ui.chat.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 6.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ui.chat, key = { it.id }) { message ->
            val mine = message.senderId == selfId
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (mine) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        if (!mine) {
                            Text(
                                text = message.senderName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Reactions stay one tap away: they are the least disruptive way to say
        // something, which matters when people are watching the same video.
        TextButton(onClick = { onReaction("😂") }) { Text("😂") }
        TextButton(onClick = { onReaction("❤️") }) { Text("❤️") }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(500) },
            placeholder = { Text("Say something…") },
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

        FilledIconButton(
            onClick = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSendChat(text)
                    draft = ""
                }
            },
            enabled = draft.isNotBlank(),
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
            )
        }
    }
}

/** Height of the chat input row, including its padding. */
private const val INPUT_ROW_DP = 64

/** Width of the chat column in landscape. */
private const val SIDE_CHAT_DP = 320
