package eu.kanade.tachiyomi.ui.player.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The chat panel that occupies the space the video does not need.
 *
 * ## Where it sits
 *
 * The activity shrinks the mpv surface to the video's own height, so this panel
 * starts exactly where the picture ends. That is the whole point of the Watch
 * Party layout: the black band that used to surround the video *becomes* the
 * conversation, with no scrolling of the video and no floating overlay covering
 * the picture.
 *
 * ## Sizing rules
 *
 *  - The status row and the input are fixed; only the message list flexes.
 *  - `imePadding` keeps the input above the keyboard, and the list shrinks
 *    instead. Typing must never push the video off screen or hide the field.
 *  - `navigationBarsPadding` keeps the input clear of the gesture bar.
 */
@Composable
fun WatchPartyPanel(
    state: WatchPartyUiState,
    selfId: String,
    localReady: Boolean,
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onCancelCountdown: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            PanelHeader(
                state = state,
                localReady = localReady,
                onReady = onReady,
                onNotReady = onNotReady,
                onCancelCountdown = onCancelCountdown,
                onSyncNow = onSyncNow,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            MessageList(
                state = state,
                selfId = selfId,
                modifier = Modifier.weight(1f),
            )

            ChatComposer(onSendChat = onSendChat, onReaction = onReaction)
        }
    }
}

@Composable
private fun PanelHeader(
    state: WatchPartyUiState,
    localReady: Boolean,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onCancelCountdown: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "${state.participants.size} watching",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                state.episodeMatch?.let { match ->
                    Text(
                        text = when (match) {
                            EpisodeMatch.EXACT -> "Same episode"
                            EpisodeMatch.PROBABLE -> "Probably the same episode"
                            EpisodeMatch.UNRESOLVED -> "Episode not open"
                            EpisodeMatch.MISMATCH -> "Different episode"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (match) {
                            EpisodeMatch.EXACT -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onSyncNow, enabled = !state.isSyncing) { Text("Sync") }
                TextButton(onClick = if (localReady) onNotReady else onReady) {
                    Text(if (localReady) "Not ready" else "Ready")
                }
            }
        }

        state.countdown?.let { countdown ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Starting in ${countdown.remainingSeconds(System.currentTimeMillis())}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onCancelCountdown) { Text("Cancel") }
            }
        }

        // An unresolved or mismatched episode is the most likely reason a guest is
        // out of sync, so it is stated here instead of hidden in a sheet.
        (state.warning ?: state.error)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MessageList(
    state: WatchPartyUiState,
    selfId: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow new messages only when the user is already at the bottom; yanking
    // them away from history they are reading would be hostile.
    LaunchedEffect(state.chat.size) {
        if (state.chat.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= state.chat.lastIndex - 2
        val mine = state.chat.last().senderId == selfId
        if (nearBottom || mine) {
            listState.animateScrollToItem(state.chat.lastIndex)
        }
    }

    if (state.chat.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "No messages yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.chat, key = { it.id }) { message ->
            val mine = message.senderId == selfId
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (!mine) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (mine) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    onSendChat: (String) -> Unit,
    onReaction: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onSendChat(text)
        draft = ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Reactions are the least disruptive way to speak while watching, so they
        // stay one tap from the field rather than behind a menu.
        TextButton(onClick = { onReaction("😂") }) { Text("😂") }
        TextButton(onClick = { onReaction("❤️") }) { Text("❤️") }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(RoomMessageCodec.MAX_CHAT_LENGTH) },
            placeholder = { Text("Message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )

        FilledIconButton(onClick = { send() }, enabled = draft.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

