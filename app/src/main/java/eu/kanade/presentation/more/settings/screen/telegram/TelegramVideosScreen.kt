package eu.kanade.presentation.more.settings.screen.telegram

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.tachiyomi.data.telegram.Td
import eu.kanade.tachiyomi.data.telegram.TdHttpServer
import eu.kanade.tachiyomi.data.telegram.TelegramVideo
import eu.kanade.tachiyomi.data.telegram.TelegramWeb
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TD_PAGE = 60
private const val TD_FIRST_WALK = 4
private const val TD_MORE_WALK = 2
private const val SEARCH_PAGE = 60
private const val SEARCH_MAX_PAGES = 60
private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_PER_SECOND = 1000L
private const val BYTES_PER_MB = 1048576.0
private const val MB_PER_GB = 1024.0
private const val TILE_ASPECT_RATIO = 16f / 9f
private const val POSTER_ASPECT_RATIO = 2f / 3f

private enum class TgView(val key: String, val label: String) {
    LIST("list", "List"),
    TILE("tile", "Tiles"),
    POSTER("poster", "Posters"),
    ;

    companion object {
        fun fromKey(key: String?): TgView = entries.firstOrNull { it.key == key } ?: LIST
    }
}

private data class TgWalk(
    val videos: List<Td.ChatVideo>,
    val more: Boolean,
    val cursor: Long,
)

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun TelegramChatVideos(
    chatId: Long,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }
    val scope = rememberCoroutineScope()
    var videos by remember(chatId) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var note by remember(chatId) { mutableStateOf<String?>(null) }
    var cursor by remember(chatId) { mutableStateOf(0L) }
    var reachedEnd by remember(chatId) { mutableStateOf(false) }
    var loadingMore by remember(chatId) { mutableStateOf(false) }
    var searchOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var query by rememberSaveable(chatId) { mutableStateOf("") }

    val viewKey by telegramPrefs.viewMode().collectAsStatePref()
    val view = TgView.fromKey(viewKey)
    val searchInKey by telegramPrefs.searchIn().collectAsStatePref()
    val searchIn = Td.SearchIn.fromKey(searchInKey)

    LaunchedEffect(chatId) {
        val walk = withContext(Dispatchers.IO) {
            walkChatVideos(chatId, 0L, TD_FIRST_WALK, TD_PAGE)
        }
        videos = walk.videos
        cursor = walk.cursor
        reachedEnd = !walk.more || walk.cursor == 0L
        note = if (walk.videos.isEmpty()) {
            withContext(Dispatchers.IO) { Td.chatVideosPage(chatId, 0L, TD_PAGE).note }
        } else {
            null
        }
    }

    fun loadOlder() {
        val current = videos.orEmpty()
        if (loadingMore || reachedEnd) return
        loadingMore = true
        scope.launch {
            val walk = withContext(Dispatchers.IO) {
                walkChatVideos(chatId, cursor, TD_MORE_WALK, TD_PAGE)
            }
            loadingMore = false
            val seen = current.map { it.messageId }.toHashSet()
            val added = walk.videos.filter { seen.add(it.messageId) }
            videos = current + added
            cursor = walk.cursor
            if (added.isEmpty() && (!walk.more || walk.cursor == 0L)) {
                reachedEnd = true
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val entries = TgView.entries
                    val next = entries[(entries.indexOf(view) + 1) % entries.size]
                    telegramPrefs.viewMode().set(next.key)
                },
            ) {
                Icon(
                    imageVector = when (view) {
                        TgView.LIST -> Icons.AutoMirrored.Outlined.ViewList
                        TgView.TILE -> Icons.Outlined.GridView
                        TgView.POSTER -> Icons.Outlined.ViewModule
                    },
                    contentDescription = "View: ${view.label}",
                )
            }
            IconButton(
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
            ) {
                Icon(
                    imageVector = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search videos",
                )
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search videos or #tags in this chat") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Look in",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Td.SearchIn.entries.forEach { mode ->
                    TgPill(
                        label = searchInLabel(mode),
                        selected = searchIn == mode,
                        onClick = { telegramPrefs.searchIn().set(mode.key) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val loaded = videos
        if (searchOpen && query.isNotBlank()) {
            ChatVideoSearchResults(
                chatId = chatId,
                query = query,
                searchIn = searchIn,
                view = view,
                onPlay = { playTdVideo(context, scope, it) },
            )
            return@Column
        }
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (loaded.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyScreen(
                    message = note ?: "No videos found in this chat yet.",
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (loadingMore) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Looking further back…", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (!reachedEnd) {
                    TextButton(onClick = { loadOlder() }) {
                        Text("Search further back")
                    }
                } else {
                    Text(
                        text = "That is the oldest post in this chat.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        ChatVideoCollection(
            videos = loaded,
            view = view,
            onPlay = { playTdVideo(context, scope, it) },
        ) {
            item(key = "telegram-td-more") {
                if (reachedEnd) {
                    Text(
                        text = "That is the oldest post in this chat.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    TextButton(
                        enabled = !loadingMore,
                        onClick = { loadOlder() },
                    ) {
                        Text(if (loadingMore) "Loading…" else "Load older")
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun TelegramChannelVideos(
    channel: String,
    title: String,
    onBack: () -> Unit,
    onTitle: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember(channel) { mutableStateOf<List<TelegramVideo>?>(null) }
    var reachedEnd by remember(channel) { mutableStateOf(false) }
    var loadingMore by remember(channel) { mutableStateOf(false) }
    var unpublished by remember(channel) { mutableIntStateOf(0) }

    LaunchedEffect(channel) {
        val page = withContext(Dispatchers.IO) { TelegramWeb.loadPage(channel) }
        videos = page?.videos ?: emptyList()
        unpublished = page?.unpublished ?: 0
        reachedEnd = page?.videos.isNullOrEmpty()
        page?.title?.takeIf { it.isNotBlank() }?.let(onTitle)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val list = videos
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (list.isEmpty()) {
            val msg = if (unpublished > 0) {
                "Telegram withheld $unpublished video post(s) from public web preview. " +
                    "Sign in to Telegram to stream them directly."
            } else {
                "No playable public videos found in $channel."
            }
            EmptyScreen(message = msg)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unpublished > 0) {
                item(key = "telegram-unpublished-note") {
                    Text(
                        text = "$unpublished video post(s) require signing in to your Telegram account.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            items(list, key = { it.channel + "/" + it.messageId }) { video ->
                TelegramVideoRow(video = video) {
                    playTelegramVideo(context, video)
                }
            }
            item(key = "telegram-more") {
                if (reachedEnd) {
                    Text(
                        text = "That is the end of this channel's public page.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    TextButton(
                        enabled = !loadingMore,
                        onClick = {
                            val last = list.lastOrNull() ?: return@TextButton
                            loadingMore = true
                            scope.launch {
                                val page = withContext(Dispatchers.IO) {
                                    TelegramWeb.load(channel, before = last.messageId)
                                }
                                loadingMore = false
                                val older = page?.second.orEmpty().filterNot { v ->
                                    list.any { it.messageId == v.messageId }
                                }
                                if (older.isEmpty()) {
                                    reachedEnd = true
                                } else {
                                    videos = list + older
                                }
                            }
                        },
                    ) {
                        Text(if (loadingMore) "Loading…" else "Load older")
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun ChatVideoSearchResults(
    chatId: Long,
    query: String,
    searchIn: Td.SearchIn,
    view: TgView,
    onPlay: (Td.ChatVideo) -> Unit,
) {
    var results by remember(chatId, query, searchIn) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var cursor by remember(chatId, query, searchIn) { mutableStateOf(0L) }
    var scanned by remember(chatId, query, searchIn) { mutableIntStateOf(0) }
    var done by remember(chatId, query, searchIn) { mutableStateOf(false) }
    var busy by remember(chatId, query, searchIn) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId, query, searchIn) {
        results = null
        scanned = 0
        done = false
        cursor = 0L
        val acc = ArrayList<Td.ChatVideo>()
        val seen = HashSet<Long>()
        var cur = 0L
        var pages = 0
        while (pages < SEARCH_MAX_PAGES) {
            pages++
            val page = withContext(Dispatchers.IO) {
                Td.searchChatVideos(chatId, query, searchIn, cur, SEARCH_PAGE)
            }
            val add = page.videos.filter { seen.add(it.messageId) }
            if (add.isNotEmpty()) acc.addAll(add)
            scanned += page.scanned
            cursor = page.nextCursor
            if (acc.isNotEmpty()) results = acc.toList()
            if (page.exhausted || page.nextCursor == 0L || (page.videos.isEmpty() && page.scanned == 0)) {
                done = true
                break
            }
            cur = page.nextCursor
        }
        results = acc.toList()
        if (acc.isEmpty()) done = true
    }

    val found = results
    if (found == null || (found.isEmpty() && !done)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (scanned > 0) "Looking through this chat… $scanned post(s) so far" else "Searching…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    if (found.isEmpty()) {
        val emptyMsg = when (searchIn) {
            Td.SearchIn.CHAT -> "No post in this chat has that in its text."
            Td.SearchIn.VIDEO -> "No video in this chat has that in its file name."
            Td.SearchIn.BOTH -> "No video in this chat matches that in its text or file name."
        }
        EmptyScreen(message = emptyMsg)
        return
    }

    ChatVideoCollection(videos = found, view = view, onPlay = onPlay) {
        item(key = "telegram-td-search-footer") {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    text = "Looked at $scanned post(s) • ${found.size} video(s) found.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!done && cursor != 0L) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val page = withContext(Dispatchers.IO) {
                                    Td.searchChatVideos(chatId, query, searchIn, cursor, SEARCH_PAGE)
                                }
                                busy = false
                                val have = results.orEmpty()
                                results = have + page.videos.filterNot { v -> have.any { it.messageId == v.messageId } }
                                cursor = page.nextCursor
                                scanned += page.scanned
                                done = page.exhausted
                            }
                        },
                    ) {
                        Text(if (busy) "Searching…" else "Search further back")
                    }
                } else {
                    Text(
                        text = "That is the whole chat.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("UnstableCollections")
private fun ChatVideoCollection(
    videos: List<Td.ChatVideo>,
    view: TgView,
    onPlay: (Td.ChatVideo) -> Unit,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = when (view) {
            TgView.LIST -> GridCells.Fixed(1)
            TgView.TILE -> GridCells.Adaptive(168.dp)
            TgView.POSTER -> GridCells.Adaptive(112.dp)
        },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(videos, key = { it.chatId.toString() + "/" + it.messageId }) { video ->
            when (view) {
                TgView.LIST -> TdVideoRow(video) { onPlay(video) }
                TgView.TILE -> TdVideoTile(video) { onPlay(video) }
                TgView.POSTER -> TdVideoPoster(video) { onPlay(video) }
            }
        }
        footer?.invoke(this)
    }
}

@Composable
private fun TdVideoThumb(
    video: Td.ChatVideo,
    modifier: Modifier,
) {
    var path by remember(video.thumbFileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(video.thumbFileId) {
        if (video.thumbFileId == 0) return@LaunchedEffect
        path = withContext(Dispatchers.IO) { Td.thumbPath(video.thumbFileId) }
    }
    val inlineBitmap = remember(video.thumb) {
        val raw = video.thumb ?: return@remember null
        val b64 = raw.substringAfter("base64,", "")
        if (b64.isEmpty()) return@remember null
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val fileModel = remember(path) { path?.let { File(it) } }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            fileModel != null -> AsyncImage(
                model = fileModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            inlineBitmap != null -> Image(
                bitmap = inlineBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TdVideoRow(video: Td.ChatVideo, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TdVideoThumb(
            video = video,
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VideoMeta(video)
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TdVideoTile(video: Td.ChatVideo, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onPlay)
            .padding(8.dp),
    ) {
        TdVideoThumb(
            video = video,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(TILE_ASPECT_RATIO)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        VideoMeta(video)
    }
}

@Composable
private fun TdVideoPoster(video: Td.ChatVideo, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onPlay)
            .padding(6.dp),
    ) {
        TdVideoThumb(
            video = video,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VideoMeta(video: Td.ChatVideo) {
    val resTag = if (video.width > 0 || video.height > 0) {
        Td.resolutionTag(video.width, video.height)
    } else {
        null
    }
    val meta = listOfNotNull(
        resTag,
        stamp(video.date),
        duration(video.duration),
        sizeLabel(video.size),
    ).joinToString(" · ")
    if (meta.isNotBlank()) {
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TelegramVideoRow(video: TelegramVideo, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (!video.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = video.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                video.dateLabel,
                video.duration?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun TgPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private suspend fun walkChatVideos(
    chatId: Long,
    from: Long,
    pages: Int,
    perPage: Int,
): TgWalk {
    val found = ArrayList<Td.ChatVideo>()
    var cursor = from
    var more = false
    var walked = 0
    while (walked < pages) {
        val page = Td.chatVideosPage(chatId, cursor, perPage)
        walked++
        if (page.nextCursor == 0L) {
            more = false
            break
        }
        cursor = page.nextCursor
        more = page.more
        if (page.videos.isNotEmpty()) {
            found += page.videos
            break
        }
        if (!page.more) break
    }
    return TgWalk(found, more, cursor)
}

private fun searchInLabel(mode: Td.SearchIn): String = when (mode) {
    Td.SearchIn.CHAT -> "Post text"
    Td.SearchIn.VIDEO -> "Video names"
    Td.SearchIn.BOTH -> "Both"
}

private fun stamp(seconds: Int): String? {
    if (seconds <= 0) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * MILLIS_PER_SECOND))
    }.getOrNull()
}

private fun duration(seconds: Int): String? {
    if (seconds <= 0) return null
    val m = seconds / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(Locale.US, m, s)
}

private fun sizeLabel(bytes: Long): String? {
    if (bytes <= 0) return null
    val mb = bytes / BYTES_PER_MB
    return if (mb >= MB_PER_GB) {
        String.format(Locale.US, "%.1f GB", mb / MB_PER_GB)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

internal fun playTdVideo(context: Context, scope: CoroutineScope, video: Td.ChatVideo) {
    scope.launch {
        runCatching { Td.prewarm(video.fileId) }
        val urlResult = withContext(Dispatchers.IO) {
            runCatching { TdHttpServer.urlFor(video.fileId, video.chatId, video.messageId) }
        }
        val streamUrl = urlResult.getOrNull()
        if (streamUrl == null) {
            context.toast("Couldn't start Telegram stream")
            return@launch
        }
        runCatching {
            context.startActivity(
                PlayerActivity.newDirectIntent(
                    context = context,
                    title = video.title,
                    videoUrl = streamUrl,
                    sourceLabel = "Telegram",
                    width = video.width,
                    height = video.height,
                ),
            )
        }.onFailure {
            context.toast("Couldn't open the player")
        }
    }
}

internal fun playTelegramVideo(context: Context, video: TelegramVideo) {
    val networkHelper = Injekt.get<NetworkHelper>()
    val headers = mapOf(
        "User-Agent" to networkHelper.defaultUserAgentProvider(),
        "Referer" to "https://t.me/",
    )
    runCatching {
        context.startActivity(
            PlayerActivity.newDirectIntent(
                context = context,
                title = video.title,
                videoUrl = video.url,
                sourceLabel = "Telegram",
                headers = headers,
            ),
        )
    }.onFailure {
        context.toast("Couldn't open the player")
    }
}
