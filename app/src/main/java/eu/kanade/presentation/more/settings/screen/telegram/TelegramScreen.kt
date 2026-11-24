package eu.kanade.presentation.more.settings.screen.telegram

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.TelegramBrandLogo
import eu.kanade.presentation.more.settings.screen.TelegramMediaCacheDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.telegram.Td
import eu.kanade.tachiyomi.data.telegram.TelegramBrowseSources
import eu.kanade.tachiyomi.data.telegram.TelegramChannels
import eu.kanade.tachiyomi.data.telegram.TelegramLinks
import eu.kanade.tachiyomi.data.telegram.TelegramWeb
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private enum class TgSection { CHATS, ADDED, LINKS }

private enum class ChatKindFilter { ALL, CHANNELS, BOTS, GROUPS }

private const val QR_CODE_SIZE_PX = 512

class TelegramScreen : Screen() {

    @Composable
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }
        val channelsRaw by telegramPrefs.channels().collectAsStatePref()
        val linksRaw by telegramPrefs.links().collectAsStatePref()
        val browseSourcesRaw by telegramPrefs.browseSources().collectAsStatePref()
        val cacheMedia by telegramPrefs.cacheMedia().collectAsStatePref()
        val channels = remember(channelsRaw) { TelegramChannels.decode(channelsRaw) }
        val links = remember(linksRaw) { TelegramLinks.decode(linksRaw) }

        var openName by rememberSaveable { mutableStateOf<String?>(null) }
        var openChat by rememberSaveable { mutableStateOf(0L) }
        var openChatTitle by rememberSaveable { mutableStateOf("") }
        var section by rememberSaveable { mutableStateOf(TgSection.CHATS) }
        var addPicker by remember { mutableStateOf(false) }
        var addOpen by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        var adding by remember { mutableStateOf(false) }
        var addError by remember { mutableStateOf("") }
        var linkOpen by remember { mutableStateOf(false) }
        var linkTyped by remember { mutableStateOf("") }
        var linking by remember { mutableStateOf(false) }
        var linkError by remember { mutableStateOf("") }
        var gearOpen by remember { mutableStateOf(false) }
        var longPressedChat by remember { mutableStateOf<Td.Chat?>(null) }
        var longPressedPublicChannel by remember { mutableStateOf<Pair<String, String>?>(null) }
        var savingExtension by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { Td.init(context) }

        val openChannel = channels.firstOrNull { it.first.equals(openName, ignoreCase = true) }

        BackHandler(enabled = openChannel != null || openChat != 0L) {
            if (openChat != 0L) {
                openChat = 0L
                openChatTitle = ""
            } else {
                openName = null
            }
        }

        fun setCacheMediaEnabled(enabled: Boolean) {
            telegramPrefs.cacheMedia().set(enabled)
            if (!enabled) {
                scope.launch {
                    withContext(Dispatchers.IO) { Td.purgeMediaFiles(context) }
                    context.toast("Telegram media caching disabled")
                }
            } else {
                context.toast("Telegram media caching enabled")
            }
        }

        fun addChannel() {
            val handle = TelegramWeb.normalize(typed)
            if (handle == null) {
                addError = "Enter a channel name like @TelegramTips or a t.me/ link"
                return
            }
            adding = true
            addError = ""
            scope.launch {
                val tdFound = if (Td.isSignedIn()) {
                    withContext(Dispatchers.IO) {
                        runCatching { Td.joinedChat(handle) ?: Td.publicChat(handle) }.getOrNull()
                    }
                } else {
                    null
                }
                if (tdFound != null) {
                    val (_, tdTitle) = tdFound
                    TelegramChannels.add(telegramPrefs, channels, handle, tdTitle.ifBlank { handle })
                    adding = false
                    addOpen = false
                    typed = ""
                    section = TgSection.ADDED
                    openName = handle
                    return@launch
                }
                val page = withContext(Dispatchers.IO) {
                    runCatching { TelegramWeb.loadPage(handle) }.getOrNull()
                }
                adding = false
                if (page == null) {
                    addError = "Could not load channel. Check the @name or your internet connection."
                } else {
                    val foundTitle = page.title?.takeIf { it.isNotBlank() } ?: handle
                    TelegramChannels.add(telegramPrefs, channels, handle, foundTitle)
                    addOpen = false
                    typed = ""
                    section = TgSection.ADDED
                    openName = handle
                }
            }
        }

        fun addLink() {
            val parsed = TelegramLinks.normalize(linkTyped)
            if (parsed == null) {
                linkError = "Paste a video post link like https://t.me/channel/123"
                return
            }
            linking = true
            linkError = ""
            scope.launch {
                val described = withContext(Dispatchers.IO) { TelegramLinks.describe(parsed) }
                TelegramLinks.add(telegramPrefs, links, described)
                linking = false
                linkOpen = false
                linkTyped = ""
                section = TgSection.LINKS
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                if (openChat == 0L && openChannel == null) {
                    AppBar(
                        title = "Telegram",
                        subtitle = "Your chats, channels & bots",
                        navigateUp = navigator::pop,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = "Add",
                                        icon = Icons.Outlined.Add,
                                        onClick = { addPicker = true },
                                    ),
                                    AppBar.Action(
                                        title = "Settings",
                                        icon = Icons.Outlined.Settings,
                                        onClick = { gearOpen = true },
                                    ),
                                ),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when {
                    openChat != 0L -> {
                        TelegramChatVideos(
                            chatId = openChat,
                            title = openChatTitle.ifBlank { "Chat" },
                            onBack = {
                                openChat = 0L
                                openChatTitle = ""
                            },
                        )
                    }
                    openChannel != null -> {
                        TelegramChannelVideos(
                            channel = openChannel.first,
                            title = openChannel.second.ifBlank { openChannel.first },
                            onBack = { openName = null },
                            onTitle = { fixed ->
                                TelegramChannels.setTitle(telegramPrefs, channels, openChannel.first, fixed)
                            },
                        )
                    }
                    else -> {
                        TelegramHome(
                            channels = channels,
                            links = links,
                            browseSourcesRaw = browseSourcesRaw,
                            section = section,
                            onSection = { section = it },
                            onOpenChannel = { openName = it },
                            onOpenChat = { id, title ->
                                openChatTitle = title
                                openChat = id
                            },
                            onLongPressChat = { chat -> longPressedChat = chat },
                            onLongPressPublicChannel = { handle, title ->
                                longPressedPublicChannel = handle to title
                            },
                            onAddChannel = { addPicker = true },
                            onAddLink = {
                                linkError = ""
                                linkOpen = true
                            },
                            onRemoveChannel = { handle ->
                                TelegramChannels.remove(telegramPrefs, channels, handle)
                            },
                            onRemoveLink = { url ->
                                TelegramLinks.remove(telegramPrefs, links, url)
                            },
                        )
                    }
                }
            }
        }

        if (gearOpen) {
            TelegramMediaCacheDialog(
                cacheMedia = cacheMedia,
                onToggleCacheMedia = ::setCacheMediaEnabled,
                onDismiss = { gearOpen = false },
            )
        }

        longPressedChat?.let { targetChat ->
            val alreadyInBrowse = remember(browseSourcesRaw, targetChat.id) {
                TelegramBrowseSources.containsChat(browseSourcesRaw, targetChat.id)
            }
            val kindText = when (targetChat.kind) {
                Td.Chat.Kind.BOT -> "Telegram Bot"
                Td.Chat.Kind.CHANNEL -> "Telegram Channel"
                Td.Chat.Kind.GROUP -> "Telegram Group"
                Td.Chat.Kind.SAVED -> "Saved Messages"
                else -> "Telegram Chat"
            }
            AlertDialog(
                onDismissRequest = { if (!savingExtension) longPressedChat = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TelegramChatAvatar(
                            photoFileId = targetChat.photoFileId,
                            kind = targetChat.kind,
                            size = 44.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = targetChat.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = kindText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = if (alreadyInBrowse) {
                            "This $kindText is active in your Browse section " +
                                "(Sources & Extensions) with its original logo."
                        } else {
                            "Add this $kindText as an extension in the Browse section " +
                                "(Sources & Extensions) with its original logo so you can " +
                                "browse, search, and watch its videos like any extension."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = !savingExtension,
                        onClick = {
                            savingExtension = true
                            scope.launch {
                                if (alreadyInBrowse) {
                                    TelegramBrowseSources.removeChat(telegramPrefs, targetChat.id)
                                    savingExtension = false
                                    longPressedChat = null
                                    context.toast("Removed ${targetChat.title} from Browse extensions")
                                } else {
                                    withContext(Dispatchers.IO) {
                                        TelegramBrowseSources.addOrUpdateChat(
                                            context = context,
                                            prefs = telegramPrefs,
                                            chat = targetChat,
                                        )
                                    }
                                    savingExtension = false
                                    longPressedChat = null
                                    context.toast("Added ${targetChat.title} as extension in Browse")
                                }
                            }
                        },
                    ) {
                        if (savingExtension) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (alreadyInBrowse) {
                                "Remove from Browse"
                            } else {
                                "Add as extension in Browse"
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !savingExtension,
                        onClick = { longPressedChat = null },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }

        longPressedPublicChannel?.let { (handle, title) ->
            val alreadyInBrowse = remember(browseSourcesRaw, handle) {
                TelegramBrowseSources.containsHandle(browseSourcesRaw, handle)
            }
            AlertDialog(
                onDismissRequest = { if (!savingExtension) longPressedPublicChannel = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TelegramBrandLogo(size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = title.ifBlank { handle },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "@${handle.removePrefix("@")} • Public Channel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = if (alreadyInBrowse) {
                            "This public channel is currently registered as an extension in Browse."
                        } else {
                            "Add this public channel as an extension in the Browse section " +
                                "with its original channel logo."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = !savingExtension,
                        onClick = {
                            savingExtension = true
                            scope.launch {
                                if (alreadyInBrowse) {
                                    TelegramBrowseSources.removeHandle(telegramPrefs, handle)
                                    savingExtension = false
                                    longPressedPublicChannel = null
                                    context.toast("Removed $title from Browse extensions")
                                } else {
                                    withContext(Dispatchers.IO) {
                                        TelegramBrowseSources.addOrUpdatePublicChannel(
                                            context = context,
                                            prefs = telegramPrefs,
                                            handle = handle,
                                            title = title.ifBlank { handle },
                                        )
                                    }
                                    savingExtension = false
                                    longPressedPublicChannel = null
                                    context.toast("Added ${title.ifBlank { handle }} as extension in Browse")
                                }
                            }
                        },
                    ) {
                        if (savingExtension) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (alreadyInBrowse) {
                                "Remove from Browse"
                            } else {
                                "Add as extension in Browse"
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !savingExtension,
                        onClick = { longPressedPublicChannel = null },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (addPicker) {
            AlertDialog(
                onDismissRequest = { addPicker = false },
                title = { Text("Add to Telegram") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddOptionRow(
                            icon = Icons.Outlined.Tag,
                            title = "Public channel",
                            subtitle = "Add a channel by @name or t.me link to browse its public videos",
                            onClick = {
                                addPicker = false
                                addError = ""
                                addOpen = true
                            },
                        )
                        AddOptionRow(
                            icon = Icons.Outlined.Link,
                            title = "Video post link",
                            subtitle = "Paste a t.me/channel/123 post link to save and stream a video",
                            onClick = {
                                addPicker = false
                                linkError = ""
                                linkOpen = true
                            },
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { addPicker = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (addOpen) {
            AlertDialog(
                onDismissRequest = { if (!adding) addOpen = false },
                title = { Text("Add public channel") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = {
                                typed = it
                                addError = ""
                            },
                            label = { Text("@channel or t.me link") },
                            singleLine = true,
                            isError = addError.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (addError.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = addError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Only public channels can be read without signing in to Telegram.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !adding && typed.isNotBlank(), onClick = { addChannel() }) {
                        if (adding) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Add")
                        }
                    }
                },
                dismissButton = {
                    TextButton(enabled = !adding, onClick = { addOpen = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (linkOpen) {
            AlertDialog(
                onDismissRequest = { if (!linking) linkOpen = false },
                title = { Text("Add a video link") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = linkTyped,
                            onValueChange = {
                                linkTyped = it
                                linkError = ""
                            },
                            label = { Text("t.me link to a video post") },
                            singleLine = true,
                            isError = linkError.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (linkError.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = linkError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Paste a link like t.me/channel/123. Plays via your signed-in " +
                                "account or from Telegram's public preview when available.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !linking && linkTyped.isNotBlank(), onClick = { addLink() }) {
                        if (linking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Add")
                        }
                    }
                },
                dismissButton = {
                    TextButton(enabled = !linking, onClick = { linkOpen = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun AddOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod", "UnstableCollections", "CyclomaticComplexMethod")
private fun TelegramHome(
    channels: List<Pair<String, String>>,
    links: List<TelegramLinks.Link>,
    browseSourcesRaw: String,
    section: TgSection,
    onSection: (TgSection) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenChat: (Long, String) -> Unit,
    onLongPressChat: (Td.Chat) -> Unit,
    onLongPressPublicChannel: (String, String) -> Unit,
    onAddChannel: () -> Unit,
    onAddLink: () -> Unit,
    onRemoveChannel: (String) -> Unit,
    onRemoveLink: (String) -> Unit,
) {
    val auth by Td.auth.collectAsState()
    val chatList by Td.chats.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf(ChatKindFilter.ALL) }

    val channelCount = remember(chatList) { chatList.count { it.kind == Td.Chat.Kind.CHANNEL } }
    val botCount = remember(chatList) { chatList.count { it.kind == Td.Chat.Kind.BOT } }
    val groupCount = remember(chatList) { chatList.count { it.kind == Td.Chat.Kind.GROUP } }

    val visibleChats = remember(chatList, query, kindFilter) {
        val byKind = when (kindFilter) {
            ChatKindFilter.ALL -> chatList
            ChatKindFilter.CHANNELS -> chatList.filter { it.kind == Td.Chat.Kind.CHANNEL }
            ChatKindFilter.BOTS -> chatList.filter { it.kind == Td.Chat.Kind.BOT }
            ChatKindFilter.GROUPS -> chatList.filter { it.kind == Td.Chat.Kind.GROUP }
        }
        val q = query.trim()
        if (q.isEmpty()) byKind else byKind.filter { it.title.contains(q, ignoreCase = true) }
    }
    val scope = rememberCoroutineScope()
    val pages = remember { TgSection.entries }
    val pager = rememberPagerState(initialPage = pages.indexOf(section).coerceAtLeast(0)) { pages.size }

    LaunchedEffect(pager.settledPage) {
        val current = pages[pager.settledPage]
        if (current != section) onSection(current)
    }
    LaunchedEffect(section) {
        val target = pages.indexOf(section)
        if (target >= 0 && target != pager.currentPage) pager.animateScrollToPage(target)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TgPill(
                label = if (chatList.isNotEmpty()) "Chats (${chatList.size})" else "Chats",
                selected = section == TgSection.CHATS,
                onClick = {
                    onSection(TgSection.CHATS)
                    scope.launch { pager.animateScrollToPage(0) }
                },
            )
            TgPill(
                label = if (channels.isNotEmpty()) "Channels (${channels.size})" else "Channels",
                selected = section == TgSection.ADDED,
                onClick = {
                    onSection(TgSection.ADDED)
                    scope.launch { pager.animateScrollToPage(1) }
                },
            )
            TgPill(
                label = if (links.isNotEmpty()) "Links (${links.size})" else "Links",
                selected = section == TgSection.LINKS,
                onClick = {
                    onSection(TgSection.LINKS)
                    scope.launch { pager.animateScrollToPage(2) }
                },
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { pageIdx ->
            when (pages[pageIdx]) {
                TgSection.CHATS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "telegram-account") {
                            TelegramAccountCard()
                        }
                        if (auth is Td.Auth.Ready && chatList.isNotEmpty()) {
                            item(key = "telegram-chat-search") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        placeholder = { Text("Search your channels, bots & chats") },
                                        singleLine = true,
                                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (query.isNotEmpty()) {
                                                IconButton(onClick = { query = "" }) {
                                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TgPill(
                                            label = "All (${chatList.size})",
                                            selected = kindFilter == ChatKindFilter.ALL,
                                            onClick = { kindFilter = ChatKindFilter.ALL },
                                        )
                                        TgPill(
                                            label = "Channels ($channelCount)",
                                            selected = kindFilter == ChatKindFilter.CHANNELS,
                                            onClick = { kindFilter = ChatKindFilter.CHANNELS },
                                        )
                                        TgPill(
                                            label = "Bots ($botCount)",
                                            selected = kindFilter == ChatKindFilter.BOTS,
                                            onClick = { kindFilter = ChatKindFilter.BOTS },
                                        )
                                        TgPill(
                                            label = "Groups ($groupCount)",
                                            selected = kindFilter == ChatKindFilter.GROUPS,
                                            onClick = { kindFilter = ChatKindFilter.GROUPS },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Extension,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Long-press any channel or bot to add it as an " +
                                                "extension in Browse with its original logo.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (auth is Td.Auth.Ready && chatList.isEmpty()) {
                            item(key = "telegram-chats-loading") {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Loading your channels, bots & chats…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(visibleChats, key = { "chat-" + it.id }) { chat ->
                            val isAddedToBrowse = remember(browseSourcesRaw, chat.id) {
                                TelegramBrowseSources.containsChat(browseSourcesRaw, chat.id)
                            }
                            TelegramChatRow(
                                chat = chat,
                                isAddedToBrowse = isAddedToBrowse,
                                onOpen = { onOpenChat(chat.id, chat.title) },
                                onLongPress = { onLongPressChat(chat) },
                            )
                        }
                    }
                }
                TgSection.ADDED -> {
                    if (channels.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            EmptyScreen(
                                message = "Public channels play without signing in. " +
                                    "Tap + above to add a channel by @name or t.me link.",
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            TextButton(onClick = onAddChannel) {
                                Text("Add public channel")
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(channels, key = { "ch-" + it.first }) { (handle, title) ->
                                val isAddedToBrowse = remember(browseSourcesRaw, handle) {
                                    TelegramBrowseSources.containsHandle(browseSourcesRaw, handle)
                                }
                                TelegramChannelRow(
                                    title = title.ifBlank { handle },
                                    handle = handle,
                                    isAddedToBrowse = isAddedToBrowse,
                                    onOpen = { onOpenChannel(handle) },
                                    onLongPress = { onLongPressPublicChannel(handle, title) },
                                    onRemove = { onRemoveChannel(handle) },
                                )
                            }
                        }
                    }
                }
                TgSection.LINKS -> {
                    TelegramLinksSection(
                        links = links,
                        onAddLink = onAddLink,
                        onRemoveLink = onRemoveLink,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("UnstableCollections", "ReturnCount")
private fun TelegramLinksSection(
    links: List<TelegramLinks.Link>,
    onAddLink: () -> Unit,
    onRemoveLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyUrl by remember { mutableStateOf<String?>(null) }

    fun play(link: TelegramLinks.Link) {
        if (busyUrl != null) return
        busyUrl = link.url
        scope.launch {
            val tdVideo = if (Td.isSignedIn()) {
                withContext(Dispatchers.IO) { runCatching { Td.linkVideo(link.url) }.getOrNull() }
            } else {
                null
            }
            if (tdVideo != null) {
                busyUrl = null
                playTdVideo(context, scope, tdVideo)
                return@launch
            }
            val post = withContext(Dispatchers.IO) {
                runCatching { TelegramWeb.loadPost(link.channel, link.messageId) }.getOrNull()
            }
            busyUrl = null
            val web = post?.video
            when {
                web != null -> playTelegramVideo(context, web)
                post?.withheld == true -> context.toast(
                    "Sign in to Telegram above to play private or restricted videos.",
                )
                else -> context.toast("Could not open that video link. Check it is a video post.")
            }
        }
    }

    if (links.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyScreen(
                message = "No saved video links yet. Add a t.me/channel/123 link to stream it here.",
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onAddLink) {
                Text("Add a video link")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(links, key = { "link-" + it.url }) { link ->
            TelegramLinkRow(
                link = link,
                busy = busyUrl == link.url,
                onPlay = { play(link) },
                onRemove = { onRemoveLink(link.url) },
            )
        }
    }
}

@Composable
private fun TelegramLinkRow(
    link: TelegramLinks.Link,
    busy: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(enabled = !busy, onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(88.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (link.poster.isNotBlank()) {
                AsyncImage(
                    model = link.poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = link.title.ifBlank { "Video post #${link.messageId}" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (link.channel.startsWith("c/")) {
                "Private link • #${link.messageId}"
            } else {
                "${link.channel} • #${link.messageId}"
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramChannelRow(
    title: String,
    handle: String,
    isAddedToBrowse: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isAddedToBrowse) {
                    Spacer(Modifier.width(6.dp))
                    BrowseExtensionBadge()
                }
            }
            Text(
                text = "$handle • Long-press to add to Browse",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramChatRow(
    chat: Td.Chat,
    isAddedToBrowse: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val baseLabel = when (chat.kind) {
        Td.Chat.Kind.SAVED -> "Saved Messages"
        Td.Chat.Kind.CHANNEL -> "Channel"
        Td.Chat.Kind.BOT -> "Bot"
        Td.Chat.Kind.GROUP -> "Group"
        Td.Chat.Kind.PRIVATE -> "Chat"
        Td.Chat.Kind.SECRET -> "Secret chat"
    }
    val label = if (chat.archived) "$baseLabel · Archived" else baseLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TelegramChatAvatar(
            photoFileId = chat.photoFileId,
            kind = chat.kind,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isAddedToBrowse) {
                    Spacer(Modifier.width(6.dp))
                    BrowseExtensionBadge()
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chat.unread > 0) {
            Text(
                text = chat.unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowseExtensionBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "IN BROWSE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun TelegramChatAvatar(
    photoFileId: Int,
    kind: Td.Chat.Kind,
    size: Dp = 40.dp,
) {
    val avatarPath by produceState<String?>(initialValue = null, photoFileId) {
        value = if (photoFileId != 0) {
            withContext(Dispatchers.IO) { Td.thumbPath(photoFileId) }
        } else {
            null
        }
    }
    val fallbackIcon = when (kind) {
        Td.Chat.Kind.SAVED -> Icons.Outlined.BookmarkBorder
        Td.Chat.Kind.CHANNEL -> Icons.Outlined.Tag
        Td.Chat.Kind.BOT -> Icons.Outlined.SmartToy
        else -> Icons.AutoMirrored.Outlined.Send
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarPath.isNullOrBlank()) {
            AsyncImage(
                model = File(avatarPath!!),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TelegramAccountCard() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }
    val savedId by telegramPrefs.apiId().collectAsStatePref()
    val savedHash by telegramPrefs.apiHash().collectAsStatePref()
    val auth by Td.auth.collectAsState()
    val me by Td.me.collectAsState()
    val problem by Td.problem.collectAsState()
    val busy by Td.busy.collectAsState()
    val sentTo by Td.sentTo.collectAsState()

    var apiId by remember(savedId) {
        mutableStateOf(if (savedId > 0) savedId.toString() else "")
    }
    var apiHash by remember(savedHash) { mutableStateOf(savedHash) }
    var editingKeys by remember { mutableStateOf(false) }
    var keyNote by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }

    LaunchedEffect(savedId, savedHash) {
        if (savedId > 0 && savedHash.isNotBlank()) {
            Td.setCredentials(context, savedId, savedHash)
        }
    }

    fun connectWithTypedKeys() {
        val warn = Td.keyProblem(apiId, apiHash)
        if (warn != null) {
            keyNote = warn
            return
        }
        val id = Td.apiIdOf(apiId) ?: return
        val hash = Td.apiHashOf(apiHash) ?: return
        keyNote = null
        editingKeys = false
        telegramPrefs.apiId().set(id)
        telegramPrefs.apiHash().set(hash)
        Td.setCredentials(context, id, hash)
    }

    fun useBuiltInKeys() {
        keyNote = null
        editingKeys = false
        apiId = TelegramPreferences.DEFAULT_API_ID.toString()
        apiHash = TelegramPreferences.DEFAULT_API_HASH
        telegramPrefs.apiId().set(TelegramPreferences.DEFAULT_API_ID)
        telegramPrefs.apiHash().set(TelegramPreferences.DEFAULT_API_HASH)
        Td.setCredentials(
            context,
            TelegramPreferences.DEFAULT_API_ID,
            TelegramPreferences.DEFAULT_API_HASH,
        )
    }

    fun changeKeys() {
        Td.signInAgain()
        keyNote = null
        editingKeys = true
    }

    val needsKeys = editingKeys || savedId <= 0 || savedHash.isBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TelegramBrandLogo(size = 38.dp, cornerRadius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Your Telegram",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val status = when (auth) {
                    is Td.Auth.Ready -> if (me.isNotBlank()) "Signed in as $me" else "Signed in"
                    is Td.Auth.WaitPhone -> "Enter your phone number to sign in"
                    is Td.Auth.WaitCode ->
                        if (sentTo.isNotBlank()) {
                            "Code sent to $sentTo — check the Telegram app"
                        } else {
                            "Enter the code Telegram sent you"
                        }
                    is Td.Auth.WaitPassword -> "Enter your 2-step verification password"
                    is Td.Auth.WaitRegistration -> "Finish setting up your Telegram name"
                    is Td.Auth.WaitOtherDevice -> "Scan this QR code from Telegram on your phone"
                    is Td.Auth.Starting -> "Connecting to Telegram…"
                    is Td.Auth.Unavailable -> "Native TDLib library could not be loaded"
                    is Td.Auth.Idle, is Td.Auth.Closed ->
                        "Sign in to browse all your chats, channels & bots"
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (auth is Td.Auth.Ready) {
                TextButton(onClick = { Td.signOut() }) {
                    Text("Sign out")
                }
            }
        }

        val issue = keyNote ?: problem
        if (!issue.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = issue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        keyNote = null
                        Td.clearProblem()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (needsKeys && auth !is Td.Auth.Unavailable && auth !is Td.Auth.Ready) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Hikaru includes default Telegram API keys. You can also enter custom " +
                    "api_id and api_hash from my.telegram.org → API development tools.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiId,
                onValueChange = {
                    apiId = it
                    keyNote = null
                },
                label = { Text("api_id (numbers only)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = {
                    apiHash = it
                    keyNote = null
                },
                label = { Text("api_hash (32 hex chars)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
                    onClick = { connectWithTypedKeys() },
                ) {
                    Text("Save & connect")
                }
                TextButton(onClick = { useBuiltInKeys() }) {
                    Text("Use default keys")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri("https://my.telegram.org") }
                    },
                ) {
                    Text("my.telegram.org")
                }
            }
        } else {
            when (val state = auth) {
                is Td.Auth.Unavailable -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = Td.loadError
                            ?: "libtdjni.so was not packaged for this device architecture.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is Td.Auth.Idle, is Td.Auth.Closed -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { connectWithTypedKeys() }) {
                            Text("Connect")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { changeKeys() }) {
                            Text("Change API key")
                        }
                    }
                }
                is Td.Auth.Starting -> {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Starting Telegram…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { changeKeys() }) {
                            Text("Change key")
                        }
                    }
                }
                is Td.Auth.Ready -> Unit
                is Td.Auth.WaitPhone -> {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone number (+country code)") },
                        placeholder = { Text("+1 555 123 4567") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !busy && phone.isNotBlank(),
                            onClick = { Td.submitPhone(phone) },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Sending…")
                            } else {
                                Text("Send code")
                            }
                        }
                        TextButton(enabled = !busy, onClick = { Td.requestQrLogin() }) {
                            Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("QR code")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { changeKeys() }) {
                            Text("Change keys")
                        }
                    }
                }
                is Td.Auth.WaitCode -> {
                    Spacer(Modifier.height(10.dp))
                    if (sentTo.isNotBlank()) {
                        Text(
                            text = "Code sent to $sentTo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Login code") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !busy && code.isNotBlank(),
                            onClick = { Td.submitCode(code) },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Checking…")
                            } else {
                                Text("Sign in")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = !busy, onClick = { Td.restart() }) {
                            Text("Use a different number")
                        }
                    }
                }
                is Td.Auth.WaitPassword -> {
                    Spacer(Modifier.height(10.dp))
                    if (state.hint.isNotBlank()) {
                        Text(
                            text = "Hint: ${state.hint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Two-step password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !busy && password.isNotBlank(),
                            onClick = { Td.submitPassword(password) },
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Checking…")
                            } else {
                                Text("Sign in")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = !busy, onClick = { Td.restart() }) {
                            Text("Start over")
                        }
                    }
                }
                is Td.Auth.WaitRegistration -> {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = first,
                        onValueChange = { first = it },
                        label = { Text("First name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = last,
                        onValueChange = { last = it },
                        label = { Text("Last name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !busy && first.isNotBlank(),
                            onClick = { Td.submitRegistration(first, last) },
                        ) {
                            Text(if (busy) "Creating…" else "Create account")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = !busy, onClick = { Td.restart() }) {
                            Text("Start over")
                        }
                    }
                }
                is Td.Auth.WaitOtherDevice -> {
                    Spacer(Modifier.height(10.dp))
                    val qrBitmap = remember(state.link) { generateQrBitmap(state.link) }
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Telegram QR login code",
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(8.dp),
                            )
                        }
                    }
                    Text(
                        text = "Open Telegram on your phone → Settings → Devices → Link Desktop Device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { Td.restart() }) {
                        Text("Start over")
                    }
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String): ImageBitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, QR_CODE_SIZE_PX, QR_CODE_SIZE_PX)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }.asImageBitmap()
    }.getOrNull()
}
