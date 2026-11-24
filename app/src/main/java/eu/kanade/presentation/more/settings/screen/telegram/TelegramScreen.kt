package eu.kanade.presentation.more.settings.screen.telegram

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.telegram.Td
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

private object TgSection {
    const val CHATS = "chats"
    const val ADDED = "added"
    const val LINKS = "links"
}

private const val QR_CODE_SIZE_PX = 480

@Suppress("TooManyFunctions")
class TelegramScreen : Screen() {

    @Composable
    @Suppress("LongMethod")
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        val rawChannels by telegramPrefs.channels().collectAsStatePref()
        val channels = remember(rawChannels) { TelegramChannels.decode(rawChannels) }
        val rawLinks by telegramPrefs.links().collectAsStatePref()
        val links = remember(rawLinks) { TelegramLinks.decode(rawLinks) }

        LaunchedEffect(Unit) {
            Td.init(context)
        }

        var openName by rememberSaveable { mutableStateOf<String?>(null) }
        var openChat by rememberSaveable { mutableStateOf(0L) }
        var openChatTitle by rememberSaveable { mutableStateOf("") }
        var section by rememberSaveable { mutableStateOf(TgSection.CHATS) }

        var addPicker by remember { mutableStateOf(false) }
        var addOpen by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf("") }
        var adding by remember { mutableStateOf(false) }

        var linkOpen by remember { mutableStateOf(false) }
        var linkTyped by remember { mutableStateOf("") }
        var linkError by remember { mutableStateOf("") }
        var linking by remember { mutableStateOf(false) }

        fun addChannel() {
            val handle = TelegramWeb.normalize(typed)
            if (handle == null) {
                addError = "That is not a channel — paste a @name or a t.me link."
                return
            }
            if (channels.any { it.first.equals(handle, ignoreCase = true) }) {
                addError = "That channel is already in the list."
                return
            }
            adding = true
            scope.launch {
                val info = withContext(Dispatchers.IO) { TelegramWeb.load(handle) }
                adding = false
                if (info == null) {
                    addError = "Telegram did not answer for that channel. Check the name and try again."
                } else {
                    TelegramChannels.add(telegramPrefs, channels, handle, info.first ?: handle)
                    typed = ""
                    addError = ""
                    addOpen = false
                    section = TgSection.ADDED
                }
            }
        }

        fun addLink() {
            val parsed = TelegramLinks.normalize(linkTyped)
            if (parsed == null) {
                linkError = "That is not a video link — paste a link like t.me/channel/123."
                return
            }
            if (links.any { it.url.equals(parsed.url, ignoreCase = true) }) {
                linkError = "That video link is already in the list."
                return
            }
            linking = true
            scope.launch {
                val described = withContext(Dispatchers.IO) {
                    runCatching { TelegramLinks.describe(parsed) }.getOrDefault(parsed)
                }
                linking = false
                TelegramLinks.add(telegramPrefs, links, described)
                linkTyped = ""
                linkError = ""
                linkOpen = false
                section = TgSection.LINKS
            }
        }

        BackHandler(enabled = openChat != 0L || openName != null || section != TgSection.CHATS) {
            when {
                openChat != 0L -> openChat = 0L
                openName != null -> openName = null
                else -> section = TgSection.CHATS
            }
        }

        val openChannel = channels.firstOrNull { it.first == openName }
        val topBarState = rememberTopAppBarState()
        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                if (openChat == 0L && openChannel == null) {
                    AppBar(
                        title = "Telegram",
                        subtitle = "Your chats and public channels",
                        navigateUp = navigator::pop,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = "Add to Telegram",
                                        icon = Icons.Outlined.Add,
                                        onClick = { addPicker = true },
                                    ),
                                ),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            hazeEnabled = hazeEnabled,
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                when {
                    openChat != 0L -> {
                        TelegramChatVideos(
                            chatId = openChat,
                            title = openChatTitle.ifBlank { "Chat" },
                            onBack = { openChat = 0L },
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
                            section = section,
                            onSection = { section = it },
                            onOpenChannel = { openName = it },
                            onOpenChat = { id, title ->
                                openChatTitle = title
                                openChat = id
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
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod", "UnstableCollections")
private fun TelegramHome(
    channels: List<Pair<String, String>>,
    links: List<TelegramLinks.Link>,
    section: String,
    onSection: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenChat: (Long, String) -> Unit,
    onAddChannel: () -> Unit,
    onAddLink: () -> Unit,
    onRemoveChannel: (String) -> Unit,
    onRemoveLink: (String) -> Unit,
) {
    val auth by Td.auth.collectAsState()
    val chatList by Td.chats.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val visibleChats = remember(chatList, query) {
        if (query.isBlank()) chatList
        else chatList.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    val pages = listOf(TgSection.CHATS, TgSection.ADDED, TgSection.LINKS)
    val pager = rememberPagerState(
        initialPage = pages.indexOf(section).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(section) {
        val target = pages.indexOf(section)
        if (target >= 0 && target != pager.currentPage) pager.scrollToPage(target)
    }
    LaunchedEffect(pager.currentPage) {
        val shown = pages.getOrNull(pager.currentPage)
        if (shown != null && shown != section) onSection(shown)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = { Text("Search your chats") },
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
                                        text = "Loading your chats…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(visibleChats, key = { "chat-" + it.id }) { chat ->
                            TelegramChatRow(
                                title = chat.title,
                                kind = chat.kind,
                                unread = chat.unread,
                                archived = chat.archived,
                                onOpen = { onOpenChat(chat.id, chat.title) },
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
                                TelegramChannelRow(
                                    title = title.ifBlank { handle },
                                    handle = handle,
                                    onOpen = { onOpenChannel(handle) },
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

@Composable
private fun TelegramChannelRow(
    title: String,
    handle: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = handle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove")
        }
    }
}

@Composable
private fun TelegramChatRow(
    title: String,
    kind: Td.Chat.Kind,
    unread: Int,
    archived: Boolean,
    onOpen: () -> Unit,
) {
    val icon = when (kind) {
        Td.Chat.Kind.SAVED -> Icons.Outlined.BookmarkBorder
        Td.Chat.Kind.CHANNEL -> Icons.Outlined.Tag
        else -> Icons.AutoMirrored.Outlined.Send
    }
    val baseLabel = when (kind) {
        Td.Chat.Kind.SAVED -> "Saved Messages"
        Td.Chat.Kind.CHANNEL -> "Channel"
        Td.Chat.Kind.GROUP -> "Group"
        Td.Chat.Kind.PRIVATE -> "Chat"
        Td.Chat.Kind.SECRET -> "Secret chat"
    }
    val kindLabel = if (archived) "$baseLabel · Archived" else baseLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (unread > 0) {
            Text(
                text = unread.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TelegramAccountCard() {
    val context = LocalContext.current
    val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }

    val auth by Td.auth.collectAsState()
    val me by Td.me.collectAsState()
    val online by Td.online.collectAsState()
    val problem by Td.problem.collectAsState()
    val busy by Td.busy.collectAsState()
    val sentTo by Td.sentTo.collectAsState()

    val storedId by telegramPrefs.apiId().collectAsStatePref()
    val storedHash by telegramPrefs.apiHash().collectAsStatePref()

    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(auth, storedId, storedHash) {
        if (auth is Td.Auth.Idle || auth is Td.Auth.Closed) {
            if (apiId.isBlank() && storedId > 0) apiId = storedId.toString()
            if (apiHash.isBlank() && storedHash.isNotBlank()) apiHash = storedHash
        }
    }

    fun changeKeys() {
        keyMsg = null
        telegramPrefs.apiId().set(0)
        telegramPrefs.apiHash().set("")
        Td.signInAgain()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Your Telegram",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText = when (auth) {
                        is Td.Auth.Ready ->
                            "Signed in as $me" + if (online) "" else " · connecting…"
                        is Td.Auth.Starting -> "Starting Telegram…"
                        is Td.Auth.WaitPhone -> "Enter your phone number or scan QR"
                        is Td.Auth.WaitCode -> "Enter the code Telegram sent you"
                        is Td.Auth.WaitPassword -> "Enter your two-step password"
                        is Td.Auth.WaitRegistration -> "Finish creating your account"
                        is Td.Auth.WaitOtherDevice -> "Scan this QR code in Telegram on another device"
                        is Td.Auth.Unavailable -> "Telegram library is not available in this build"
                        is Td.Auth.Closed -> "Signed out"
                        else -> "Sign in to view private channels, groups & Saved Messages"
                    }
                    Text(
                        text = statusText,
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

            problem?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val state = auth) {
                is Td.Auth.Unavailable -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Could not load TDLib on this device ABI. Public channels still work." +
                            (Td.loadError?.let { "\n$it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is Td.Auth.Ready, is Td.Auth.Starting -> Unit
                is Td.Auth.Idle, is Td.Auth.Closed -> {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiId,
                        onValueChange = {
                            apiId = it.filter { c -> c.isDigit() }
                            keyMsg = null
                        },
                        label = { Text("api_id") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiHash,
                        onValueChange = {
                            apiHash = it
                            keyMsg = null
                        },
                        label = { Text("api_hash") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Get your api_id and api_hash from my.telegram.org (API development tools).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    keyMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
                        onClick = {
                            val err = Td.keyProblem(apiId, apiHash)
                            if (err != null) {
                                keyMsg = err
                                return@TextButton
                            }
                            val id = Td.apiIdOf(apiId) ?: return@TextButton
                            val hash = Td.apiHashOf(apiHash) ?: return@TextButton
                            telegramPrefs.apiId().set(id)
                            telegramPrefs.apiHash().set(hash)
                            Td.setCredentials(context, id, hash)
                        },
                    ) {
                        Text("Save and continue")
                    }
                }
                is Td.Auth.WaitPhone -> {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = {
                            phone = it
                            Td.clearProblem()
                        },
                        label = { Text("Phone number (with country code)") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
