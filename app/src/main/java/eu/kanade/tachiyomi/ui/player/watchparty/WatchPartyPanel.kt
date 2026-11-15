package eu.kanade.tachiyomi.ui.player.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SheetBackground = Color(0xFF0F0F0F)
private val SheetActionStrip = Color(0xFF151515)
private val InputPillBackground = Color(0xFF272727)
private val DividerGray = Color(0xFF272727)
private val HandleGray = Color(0xFF606060)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFAAAAAA)
private val AccentBlue = Color(0xFF3EA6FF)
private val ReadyGreen = Color(0xFF2BA640)
private val ConnectingAmber = Color(0xFFF5A623)
private val ErrorRed = Color(0xFFFF4E45)
private val AssistantBg = Color(0xE6141414)

private val AvatarPalette = listOf(
    Color(0xFF7E57C2),
    Color(0xFF00897B),
    Color(0xFF1E88E5),
    Color(0xFFE53935),
    Color(0xFFF4511E),
    Color(0xFF43A047),
    Color(0xFF8E24AA),
    Color(0xFF3949AB),
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val THOUSAND = 1_000
private const val MILLION = 1_000_000
private const val DRAG_EXPAND_THRESHOLD_DP = 36f
private const val DRAG_CLOSE_THRESHOLD_DP = 76f
private const val ASSISTANT_SIZE_DP = 46f
private const val ASSISTANT_EDGE_DP = 8f
private const val SHEET_CORNER_DP = 16f

/**
 * YouTube Android-style Watch Party comments bottom sheet.
 *
 * Keeps real Watch Party playback controls (Ready, Sync, reactions, countdown)
 * cleanly integrated with threaded comments, likes/dislikes, replies, and fixed
 * bottom comment composer respecting Android navigation and IME insets.
 */
@Composable
fun WatchPartyPanel(
    state: WatchPartyUiState,
    selfId: String,
    selfName: String,
    localReady: Boolean,
    onSendChat: (text: String, replyToId: String?) -> Unit,
    onLikeComment: (commentId: String) -> Unit,
    onDislikeComment: (commentId: String) -> Unit,
    onReaction: (String) -> Unit,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onCancelCountdown: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenInfoSheet: () -> Unit,
    onCloseComments: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    expandedThreads: MutableMap<String, Boolean> = remember { mutableStateMapOf() },
) {
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }

    // Clear reply target if its original message dropped out of bounded history.
    LaunchedEffect(state.chat) {
        val activeReply = replyingTo
        if (activeReply != null && state.chat.none { it.id == activeReply.id }) {
            replyingTo = null
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        color = SheetBackground,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = SHEET_CORNER_DP.dp, topEnd = SHEET_CORNER_DP.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            CommentsSheetHeader(
                state = state,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onOpenInfoSheet = onOpenInfoSheet,
                onCloseComments = onCloseComments,
            )

            WatchPartyQuickStrip(
                state = state,
                localReady = localReady,
                onReady = onReady,
                onNotReady = onNotReady,
                onSyncNow = onSyncNow,
                onReaction = onReaction,
            )

            CountdownAndWarningRows(
                state = state,
                onCancelCountdown = onCancelCountdown,
            )

            HorizontalDivider(color = DividerGray, thickness = 1.dp)

            CommentsThreadList(
                state = state,
                selfId = selfId,
                listState = listState,
                expandedThreads = expandedThreads,
                onLikeComment = onLikeComment,
                onDislikeComment = onDislikeComment,
                onReply = { message -> replyingTo = message },
                modifier = Modifier.weight(1f),
            )

            HorizontalDivider(color = DividerGray, thickness = 1.dp)

            CommentComposer(
                selfId = selfId,
                selfName = selfName,
                replyingTo = replyingTo,
                onCancelReply = { replyingTo = null },
                onSendChat = { text ->
                    onSendChat(text, replyingTo?.id)
                    replyingTo = null
                },
            )
        }
    }
}

@Composable
private fun CommentsSheetHeader(
    state: WatchPartyUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenInfoSheet: () -> Unit,
    onCloseComments: () -> Unit,
) {
    val density = LocalDensity.current
    val expandThresholdPx = remember(density) { with(density) { DRAG_EXPAND_THRESHOLD_DP.dp.toPx() } }
    val closeThresholdPx = remember(density) { with(density) { DRAG_CLOSE_THRESHOLD_DP.dp.toPx() } }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val currentOnClose by rememberUpdatedState(onCloseComments)
    var cumulativeDrag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { cumulativeDrag = 0f },
                    onDragCancel = { cumulativeDrag = 0f },
                    onDragEnd = {
                        when {
                            cumulativeDrag < -expandThresholdPx -> currentOnExpandedChange(true)
                            cumulativeDrag > closeThresholdPx && !currentExpanded -> currentOnClose()
                            cumulativeDrag > expandThresholdPx && currentExpanded -> currentOnExpandedChange(false)
                        }
                        cumulativeDrag = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        cumulativeDrag += dragAmount
                    },
                )
            }
            .padding(top = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(HandleGray),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Comments",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                val statusColor = when (state.connection) {
                    ConnectionState.CONNECTED -> ReadyGreen
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING,
                    -> ConnectingAmber
                    ConnectionState.DISCONNECTED -> ErrorRed
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Text(
                        text = when (state.connection) {
                            ConnectionState.CONNECTED -> "${state.participants.size} watching"
                            ConnectionState.CONNECTING -> "Connecting…"
                            ConnectionState.RECONNECTING -> "Reconnecting…"
                            ConnectionState.DISCONNECTED -> "Offline"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onOpenInfoSheet) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Watch Party info",
                    tint = TextPrimary,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = onCloseComments) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close comments",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun WatchPartyQuickStrip(
    state: WatchPartyUiState,
    localReady: Boolean,
    onReady: () -> Unit,
    onNotReady: () -> Unit,
    onSyncNow: () -> Unit,
    onReaction: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheetActionStrip)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val readyBg = if (localReady) ReadyGreen.copy(alpha = 0.22f) else InputPillBackground
            val readyFg = if (localReady) ReadyGreen else TextPrimary
            Surface(
                shape = CircleShape,
                color = readyBg,
                modifier = Modifier.clickable {
                    if (localReady) onNotReady() else onReady()
                },
            ) {
                Text(
                    text = if (localReady) "Ready \u2713" else "Ready",
                    color = readyFg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            val canSyncNow = state.connected && state.participants.size >= 2 && !state.isSyncing
            Surface(
                shape = CircleShape,
                color = InputPillBackground,
                modifier = Modifier.clickable(enabled = !state.isSyncing) { onSyncNow() },
            ) {
                Text(
                    text = if (state.isSyncing) "Syncing…" else "Sync Now",
                    color = if (canSyncNow) AccentBlue else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReactionPill(emoji = "\uD83D\uDE02", onClick = { onReaction("\uD83D\uDE02") })
            ReactionPill(emoji = "\u2764\uFE0F", onClick = { onReaction("\u2764\uFE0F") })
        }
    }
}

@Composable
private fun ReactionPill(emoji: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(InputPillBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 14.sp)
    }
}

@Composable
private fun CountdownAndWarningRows(
    state: WatchPartyUiState,
    onCancelCountdown: () -> Unit,
) {
    state.countdown?.let { countdown ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AccentBlue.copy(alpha = 0.14f))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Starting in ${countdown.remainingSeconds(System.currentTimeMillis())}s",
                color = AccentBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onCancelCountdown) {
                Text("Cancel", color = TextPrimary, fontSize = 12.sp)
            }
        }
    }

    (state.warning ?: state.error)?.let { message ->
        Text(
            text = message,
            color = ErrorRed,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(ErrorRed.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CommentsThreadList(
    state: WatchPartyUiState,
    selfId: String,
    listState: LazyListState,
    expandedThreads: MutableMap<String, Boolean>,
    onLikeComment: (String) -> Unit,
    onDislikeComment: (String) -> Unit,
    onReply: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat
    val messageIds = remember(chat) { chat.map { it.id }.toSet() }
    val topLevel = remember(chat) {
        chat.filter { msg -> msg.replyToId == null || msg.replyToId !in messageIds }
    }
    val repliesByParent = remember(chat) {
        chat.filter { it.replyToId != null && it.replyToId in messageIds }
            .groupBy { it.replyToId!! }
    }

    LaunchedEffect(topLevel.size) {
        if (topLevel.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= topLevel.lastIndex - 2
        val mine = topLevel.last().senderId == selfId
        if (nearBottom || mine) {
            listState.animateScrollToItem(topLevel.lastIndex)
        }
    }

    if (topLevel.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No comments yet",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.connected) {
                        "Say something to start the Watch Party thread."
                    } else {
                        "Connect with the host to send and receive comments."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(topLevel, key = { it.id }) { comment ->
            val replies = repliesByParent[comment.id].orEmpty()
            val isThreadExpanded = expandedThreads[comment.id] == true
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CommentRow(
                    message = comment,
                    feedback = state.commentFeedback[comment.id] ?: CommentFeedback(),
                    selfId = selfId,
                    isReply = false,
                    onLike = { onLikeComment(comment.id) },
                    onDislike = { onDislikeComment(comment.id) },
                    onReply = { onReply(comment) },
                )

                if (replies.isNotEmpty()) {
                    val label = if (isThreadExpanded) {
                        "Hide replies"
                    } else {
                        val noun = if (replies.size == 1) "reply" else "replies"
                        "${replies.size} $noun >"
                    }
                    Text(
                        text = label,
                        color = AccentBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 42.dp)
                            .clickable { expandedThreads[comment.id] = !isThreadExpanded }
                            .padding(vertical = 2.dp),
                    )
                    if (isThreadExpanded) {
                        Column(
                            modifier = Modifier.padding(start = 42.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            replies.forEach { reply ->
                                CommentRow(
                                    message = reply,
                                    feedback = state.commentFeedback[reply.id] ?: CommentFeedback(),
                                    selfId = selfId,
                                    isReply = true,
                                    onLike = { onLikeComment(reply.id) },
                                    onDislike = { onDislikeComment(reply.id) },
                                    onReply = { onReply(comment) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    message: ChatMessage,
    feedback: CommentFeedback,
    selfId: String,
    isReply: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
) {
    val avatarSize = if (isReply) 24.dp else 30.dp
    val handle = remember(message.senderName) {
        val compact = message.senderName.trim().replace(" ", "")
        if (compact.isEmpty()) "@viewer" else "@$compact"
    }
    val likedByMe = selfId in feedback.likedBy
    val dislikedByMe = selfId in feedback.dislikedBy
    val likeCount = feedback.likedBy.size
    val dislikeCount = feedback.dislikedBy.size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CommentAvatar(
            id = message.senderId,
            name = message.senderName,
            size = avatarSize,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$handle \u2022 ${formatRelativeTime(message.timestampMs)}",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = message.text,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onLike)
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                ) {
                    Icon(
                        imageVector = if (likedByMe) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like comment",
                        tint = if (likedByMe) AccentBlue else TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    if (likeCount > 0) {
                        Text(
                            text = formatCount(likeCount),
                            color = if (likedByMe) AccentBlue else TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDislike)
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                ) {
                    Icon(
                        imageVector = if (dislikedByMe) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                        contentDescription = "Dislike comment",
                        tint = if (dislikedByMe) AccentBlue else TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    if (dislikeCount > 0) {
                        Text(
                            text = formatCount(dislikeCount),
                            color = if (dislikedByMe) AccentBlue else TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReply)
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Comment,
                        contentDescription = "Reply to comment",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentAvatar(
    id: String,
    name: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val bg = remember(id) {
        val index = abs(id.hashCode()) % AvatarPalette.size
        AvatarPalette[index]
    }
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = TextPrimary,
            fontSize = if (size < 26.dp) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CommentComposer(
    selfId: String,
    selfName: String,
    replyingTo: ChatMessage?,
    onCancelReply: () -> Unit,
    onSendChat: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onSendChat(text)
        draft = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheetBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Replying to @${replyingTo.senderName.trim().replace(" ", "")}",
                    color = AccentBlue,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cancel reply",
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onCancelReply),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CommentAvatar(id = selfId, name = selfName, size = 28.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(InputPillBackground)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = if (replyingTo != null) "Add a reply..." else "Comment...",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(RoomMessageCodec.MAX_CHAT_LENGTH) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (draft.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentBlue)
                        .clickable(onClick = ::submit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send comment",
                        tint = SheetBackground,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Compact, draggable dark circular Comments Assistant rendered over the player
 * whenever Watch Party is active and comments are closed.
 */
@Composable
fun FloatingCommentsAssistant(
    hasUnread: Boolean,
    fractionX: Float,
    fractionY: Float,
    onFractionChange: (Float, Float) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val viewConfig = LocalViewConfiguration.current
    val safeInsets = WindowInsets.safeDrawing

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()
        val buttonSizePx = with(density) { ASSISTANT_SIZE_DP.dp.toPx() }
        val edgePx = with(density) { ASSISTANT_EDGE_DP.dp.toPx() }
        val safeLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
        val safeTop = safeInsets.getTop(density).toFloat()
        val safeRight = safeInsets.getRight(density, layoutDirection).toFloat()
        val safeBottom = safeInsets.getBottom(density).toFloat()

        val minX = safeLeft + edgePx
        val minY = safeTop + edgePx
        val maxX = (containerWidthPx - safeRight - edgePx - buttonSizePx).coerceAtLeast(minX)
        val maxY = (containerHeightPx - safeBottom - edgePx - buttonSizePx).coerceAtLeast(minY)

        val currentMinX by rememberUpdatedState(minX)
        val currentMinY by rememberUpdatedState(minY)
        val currentMaxX by rememberUpdatedState(maxX)
        val currentMaxY by rememberUpdatedState(maxY)
        val currentFracX by rememberUpdatedState(fractionX.coerceIn(0f, 1f))
        val currentFracY by rememberUpdatedState(fractionY.coerceIn(0f, 1f))
        val currentOnFractionChange by rememberUpdatedState(onFractionChange)
        val currentOnClick by rememberUpdatedState(onClick)

        var activeDragX by remember { mutableFloatStateOf(-1f) }
        var activeDragY by remember { mutableFloatStateOf(-1f) }
        var isDragging by remember { mutableStateOf(false) }

        val targetX = if (isDragging && activeDragX >= 0f) {
            activeDragX.coerceIn(minX, maxX)
        } else {
            minX + (maxX - minX) * fractionX.coerceIn(0f, 1f)
        }
        val targetY = if (isDragging && activeDragY >= 0f) {
            activeDragY.coerceIn(minY, maxY)
        } else {
            minY + (maxY - minY) * fractionY.coerceIn(0f, 1f)
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) }
                .size(ASSISTANT_SIZE_DP.dp)
                .shadow(elevation = 8.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(AssistantBg)
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.22f), shape = CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "Open Watch Party comments"
                }
                .pointerInput(Unit) {
                    val touchSlop = viewConfig.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startX = currentMinX + (currentMaxX - currentMinX) * currentFracX
                        val startY = currentMinY + (currentMaxY - currentMinY) * currentFracY
                        var sumDx = 0f
                        var sumDy = 0f
                        var dragged = false
                        activeDragX = startX
                        activeDragY = startY
                        isDragging = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.position - change.previousPosition
                            sumDx += delta.x
                            sumDy += delta.y
                            if (!dragged && hypot(sumDx, sumDy) >= touchSlop) {
                                dragged = true
                                isDragging = true
                            }
                            if (dragged) {
                                change.consume()
                                val clampedX = (startX + sumDx).coerceIn(currentMinX, currentMaxX)
                                val clampedY = (startY + sumDy).coerceIn(currentMinY, currentMaxY)
                                activeDragX = clampedX
                                activeDragY = clampedY
                                val spanX = (currentMaxX - currentMinX).coerceAtLeast(1f)
                                val spanY = (currentMaxY - currentMinY).coerceAtLeast(1f)
                                currentOnFractionChange(
                                    ((clampedX - currentMinX) / spanX).coerceIn(0f, 1f),
                                    ((clampedY - currentMinY) / spanY).coerceIn(0f, 1f),
                                )
                            }
                        }
                        isDragging = false
                        activeDragX = -1f
                        activeDragY = -1f
                        if (!dragged) {
                            currentOnClick()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Comment,
                contentDescription = "Comments",
                tint = TextPrimary,
                modifier = Modifier.size(21.dp),
            )
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
            }
        }
    }
}

private fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return "just now"
    val diff = (nowMs - timestampMs).coerceAtLeast(0L)
    return when {
        diff < MINUTE_MS -> "just now"
        diff < HOUR_MS -> "${diff / MINUTE_MS}m ago"
        diff < DAY_MS -> "${diff / HOUR_MS}h ago"
        else -> "${diff / DAY_MS}d ago"
    }
}

private fun formatCount(count: Int): String = when {
    count >= MILLION -> String.format(Locale.US, "%.1fM", count / MILLION.toFloat()).removeSuffix(".0M") + "M"
    count >= THOUSAND -> String.format(Locale.US, "%.1fK", count / THOUSAND.toFloat()).removeSuffix(".0K") + "K"
    else -> count.toString()
}
