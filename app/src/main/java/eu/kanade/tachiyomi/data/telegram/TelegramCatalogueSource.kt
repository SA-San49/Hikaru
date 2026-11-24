package eu.kanade.tachiyomi.data.telegram

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

@Suppress("MagicNumber", "TooManyFunctions")
class TelegramCatalogueSource(
    val entry: TelegramBrowseSources.Entry,
) : AnimeHttpSource() {

    override val id: Long = entry.sourceId
    override val name: String = entry.title
    override val baseUrl: String = "https://t.me"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes: Boolean = false

    private val pageCursors = ConcurrentHashMap<Int, Long>()
    private val searchCursors = ConcurrentHashMap<String, Long>()

    private suspend fun resolveChatId(): Long {
        if (entry.chatId != 0L) return entry.chatId
        if (entry.handle.isNotBlank() && Td.isSignedIn()) {
            val id = Td.joinedChat(entry.handle)?.first ?: Td.publicChatId(entry.handle)
            if (id != null && id != 0L) return id
        }
        return 0L
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = loadChannelPage(page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = loadChannelPage(page)

    private suspend fun loadChannelPage(page: Int): AnimesPage {
        if (page <= 1) pageCursors.clear()
        val before = if (page <= 1) 0L else (pageCursors[page] ?: 0L)
        val chatId = resolveChatId()
        if (chatId != 0L && Td.isSignedIn()) {
            val res = Td.chatVideosPage(chatId = chatId, before = before, limit = PAGE_LIMIT)
            if (res.nextCursor != 0L) {
                pageCursors[page + 1] = res.nextCursor
            }
            val items = res.videos.map { videoToSAnime(it) }
            return AnimesPage(items, res.more && res.nextCursor != 0L && items.isNotEmpty())
        }
        if (entry.handle.isNotBlank()) {
            val webBefore = if (page <= 1) null else before.takeIf { it > 0L }
            val res = TelegramWeb.loadPage(entry.handle, before = webBefore)
                ?: return AnimesPage(emptyList(), false)
            val next = res.videos.minOfOrNull { it.messageId }?.takeIf { it > 1L } ?: 0L
            if (next != 0L) {
                pageCursors[page + 1] = next
            }
            val items = res.videos.map { webVideoToSAnime(it) }
            return AnimesPage(items, next != 0L && items.isNotEmpty())
        }
        return AnimesPage(emptyList(), false)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) return loadChannelPage(page)
        val cursorKey = "$q:$page"
        val cursor = if (page <= 1) 0L else (searchCursors[cursorKey] ?: 0L)
        val chatId = resolveChatId()
        if (chatId != 0L && Td.isSignedIn()) {
            val res = Td.searchChatVideos(
                chatId = chatId,
                text = q,
                inText = Td.SearchIn.BOTH,
                cursor = cursor,
                limit = PAGE_LIMIT,
            )
            if (res.nextCursor != 0L) {
                searchCursors["$q:${page + 1}"] = res.nextCursor
            }
            val items = res.videos.map { videoToSAnime(it) }
            return AnimesPage(items, !res.exhausted && res.nextCursor != 0L && items.isNotEmpty())
        }
        if (entry.handle.isNotBlank()) {
            val res = TelegramWeb.loadPage(entry.handle, query = q)
                ?: return AnimesPage(emptyList(), false)
            return AnimesPage(res.videos.map { webVideoToSAnime(it) }, false)
        }
        return AnimesPage(emptyList(), false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return anime.apply { initialized = true }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val uri = Uri.parse("https://t.me" + anime.url)
        val mode = uri.getQueryParameter("mode").orEmpty()
        if (mode == "web") {
            val videoUrl = uri.getQueryParameter("v").orEmpty()
            val referer = uri.getQueryParameter("ref").orEmpty()
            val title = uri.getQueryParameter("title").orEmpty().ifBlank { anime.title }
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = title
                    episode_number = 1f
                    date_upload = 0L
                    scanlator = "Telegram Web"
                    summary = referer.ifBlank { videoUrl }
                },
            )
        }

        val chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: resolveChatId()
        val msgId = uri.getQueryParameter("msg")?.toLongOrNull() ?: 0L
        val postVideos = if (chatId != 0L && msgId != 0L && Td.isSignedIn()) {
            Td.postVideos(chatId, msgId)
        } else {
            emptyList()
        }
        if (postVideos.size > 1) {
            return postVideos.mapIndexed { idx, v ->
                val epNum = (idx + 1).toFloat()
                SEpisode.create().apply {
                    url = buildTdItemUrl(v)
                    name = "Episode ${idx + 1} • " + cleanTitle(v)
                    episode_number = epNum
                    date_upload = v.date.toLong() * 1000L
                    scanlator = "${Td.resolutionTag(v.width, v.height)} • ${formatBytes(v.size)}"
                }
            }.reversed()
        }

        val single = postVideos.firstOrNull()
        val width = single?.width ?: (uri.getQueryParameter("w")?.toIntOrNull() ?: 0)
        val height = single?.height ?: (uri.getQueryParameter("h")?.toIntOrNull() ?: 0)
        val size = single?.size ?: (uri.getQueryParameter("size")?.toLongOrNull() ?: 0L)
        val dateSec = single?.date?.toLong() ?: (uri.getQueryParameter("date")?.toLongOrNull() ?: 0L)
        val epTitle = single?.let { cleanTitle(it) }
            ?: uri.getQueryParameter("title").orEmpty().ifBlank { anime.title }
        return listOf(
            SEpisode.create().apply {
                url = single?.let { buildTdItemUrl(it) } ?: anime.url
                name = epTitle
                episode_number = 1f
                date_upload = dateSec * 1000L
                scanlator = buildString {
                    append(Td.resolutionTag(width, height))
                    if (size > 0L) {
                        append(" • ")
                        append(formatBytes(size))
                    }
                }
            },
        )
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val videos = getVideoList(episode)
        val firstUrl = videos.firstOrNull()?.videoUrl.orEmpty()
        return listOf(
            Hoster(
                hosterUrl = firstUrl,
                hosterName = "Telegram HLS Stream",
                videoList = videos,
            ),
        )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val uri = Uri.parse("https://t.me" + episode.url)
        val mode = uri.getQueryParameter("mode").orEmpty()
        if (mode == "web") {
            val videoUrl = uri.getQueryParameter("v").orEmpty()
            val referer = uri.getQueryParameter("ref").orEmpty()
            val reqHeaders = Headers.Builder().apply {
                add("User-Agent", network.defaultUserAgentProvider())
                if (referer.isNotBlank()) add("Referer", referer)
            }.build()
            return Td.buildQualityVideos(
                baseUrl = videoUrl,
                width = 1920,
                height = 1080,
                headers = reqHeaders,
            )
        }
        val chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: resolveChatId()
        val msgId = uri.getQueryParameter("msg")?.toLongOrNull() ?: 0L
        var fileId = uri.getQueryParameter("file")?.toIntOrNull() ?: 0
        val width = uri.getQueryParameter("w")?.toIntOrNull() ?: 0
        val height = uri.getQueryParameter("h")?.toIntOrNull() ?: 0
        if (fileId == 0 && chatId != 0L && msgId != 0L) {
            fileId = Td.touchMessage(chatId, msgId)
        }
        if (fileId != 0) {
            Td.prewarm(fileId)
        }
        val streamUrl = TdHttpServer.urlFor(fileId, chatId, msgId)
        return Td.buildQualityVideos(
            baseUrl = streamUrl,
            width = width,
            height = height,
        )
    }

    private fun videoToSAnime(v: Td.ChatVideo): SAnime {
        val clean = cleanTitle(v)
        val resTag = Td.resolutionTag(v.width, v.height)
        return SAnime.create().apply {
            url = buildTdItemUrl(v)
            title = clean
            author = entry.title
            artist = resTag
            genre = "Telegram, $resTag"
            status = SAnime.COMPLETED
            thumbnail_url = when {
                v.thumbFileId != 0 -> TdHttpServer.thumbUrlFor(v.thumbFileId)
                !v.thumb.isNullOrBlank() -> v.thumb
                else -> null
            }
            description = buildString {
                if (v.caption.isNotBlank()) {
                    appendLine(v.caption.trim())
                    appendLine()
                }
                if (v.fileName.isNotBlank()) {
                    appendLine("File: ${v.fileName}")
                }
                append("Quality: $resTag")
                if (v.size > 0L) {
                    append(" • Size: ${formatBytes(v.size)}")
                }
                if (v.duration > 0) {
                    append(" • Duration: ${v.duration / 60}m ${v.duration % 60}s")
                }
            }
            initialized = true
        }
    }

    private fun webVideoToSAnime(v: TelegramVideo): SAnime {
        val clean = v.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Video #${v.messageId}"
        val postUrl = "https://t.me/${v.channel.removePrefix("@")}/${v.messageId}"
        return SAnime.create().apply {
            url = "/tg/item?mode=web" +
                "&post=${v.messageId}" +
                "&v=${Uri.encode(v.url)}" +
                "&ref=${Uri.encode(postUrl)}" +
                "&title=${Uri.encode(clean)}"
            title = clean
            author = entry.title
            genre = "Telegram, Public Channel"
            status = SAnime.COMPLETED
            thumbnail_url = v.posterUrl
            description = buildString {
                appendLine(v.title)
                if (!v.duration.isNullOrBlank()) {
                    append("Duration: ${v.duration}")
                }
                if (!v.dateLabel.isNullOrBlank()) {
                    append(" • ${v.dateLabel}")
                }
            }
            initialized = true
        }
    }

    private fun buildTdItemUrl(v: Td.ChatVideo): String {
        val clean = cleanTitle(v)
        return "/tg/item?mode=td" +
            "&chat=${v.chatId}" +
            "&msg=${v.messageId}" +
            "&file=${v.fileId}" +
            "&w=${v.width}" +
            "&h=${v.height}" +
            "&dur=${v.duration}" +
            "&size=${v.size}" +
            "&date=${v.date}" +
            "&title=${Uri.encode(clean)}"
    }

    private fun cleanTitle(v: Td.ChatVideo): String {
        val firstLine = v.caption.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return when {
            !firstLine.isNullOrBlank() -> firstLine.take(MAX_TITLE_LEN)
            v.fileName.isNotBlank() -> v.fileName.substringBeforeLast(".").ifBlank { v.fileName }
            else -> v.title.ifBlank { "Video #${v.messageId}" }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    override fun popularAnimeRequest(page: Int): Request =
        throw UnsupportedOperationException("Handled by getPopularAnime")

    override fun popularAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Handled by getPopularAnime")

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Handled by getLatestUpdates")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Handled by getLatestUpdates")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException("Handled by getSearchAnime")

    override fun searchAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("Handled by getSearchAnime")

    override fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException("Handled by getAnimeDetails")

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("Handled by getEpisodeList")

    override fun episodeVideoParse(response: Response): SEpisode =
        throw UnsupportedOperationException("Not used")

    override fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException("Handled by getHosterList")

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> =
        throw UnsupportedOperationException("Handled by getVideoList")

    override fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("Handled by getVideoList")

    override fun videoUrlParse(response: Response): String =
        throw UnsupportedOperationException("Handled by getVideoList")

    companion object {
        private const val PAGE_LIMIT = 40
        private const val MAX_TITLE_LEN = 90
    }
}
