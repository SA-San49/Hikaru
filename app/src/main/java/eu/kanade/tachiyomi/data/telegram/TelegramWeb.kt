package eu.kanade.tachiyomi.data.telegram

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

data class TelegramVideo(
    val channel: String,
    val messageId: Long,
    val title: String,
    val url: String,
    val posterUrl: String?,
    val duration: String?,
    val dateLabel: String?,
)

data class TelegramPost(
    val title: String?,
    val video: TelegramVideo?,
    val withheld: Boolean,
    val posterUrl: String?,
)

data class TelegramPage(
    val title: String?,
    val videos: List<TelegramVideo>,
    val unpublished: Int,
    val posts: Int = videos.size + unpublished,
)

object TelegramWeb {

    private const val BASE = "https://t.me"
    private const val DATE_PREFIX_LEN = 16
    private val CHANNEL_HANDLE_REGEX = Regex("[A-Za-z0-9_]{3,64}")

    private val networkHelper: NetworkHelper by lazy { Injekt.get() }

    fun normalize(raw: String): String? {
        var s = raw.trim()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("t.me/").removePrefix("telegram.me/").removePrefix("telegram.dog/")
        s = s.removePrefix("s/")
        s = s.substringBefore("?").substringBefore("#")
        s = s.substringBefore("/")
        s = s.removePrefix("@").trim()
        return if (s.matches(CHANNEL_HANDLE_REGEX)) "@$s" else null
    }

    private fun pageUrl(channel: String, before: Long? = null, query: String? = null): String {
        val name = channel.removePrefix("@")
        val suffix = when {
            !query.isNullOrBlank() -> "?q=" + URLEncoder.encode(query, "UTF-8")
            before != null -> "?before=$before"
            else -> ""
        }
        return "$BASE/s/$name$suffix"
    }

    fun titleOf(html: String): String? = runCatching {
        val doc = Jsoup.parse(html)
        doc.selectFirst(".tgme_channel_info_header_title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=\"og:title\"]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun load(
        channel: String,
        before: Long? = null,
        query: String? = null,
    ): Pair<String?, List<TelegramVideo>>? =
        loadPage(channel, before, query)?.let { it.title to it.videos }

    fun loadPage(
        channel: String,
        before: Long? = null,
        query: String? = null,
    ): TelegramPage? {
        val html = fetchHtml(pageUrl(channel, before, query)) ?: return null
        return parsePage(html, channel)
    }

    fun parse(html: String, channel: String): List<TelegramVideo> = parsePage(html, channel).videos

    @Suppress("CyclomaticComplexMethod")
    fun parsePage(html: String, channel: String): TelegramPage {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return TelegramPage(null, emptyList(), 0)
        val out = ArrayList<TelegramVideo>()
        var unpublished = 0
        val posts = doc.select("div.tgme_widget_message")
        for (msg in posts) {
            val id = msg.attr("data-post").substringAfterLast("/").toLongOrNull() ?: continue
            val video = msg.selectFirst("video")
            if (video == null) {
                if (msg.selectFirst(".message_media_not_supported") != null) unpublished++
                continue
            }
            val src = video.attr("src").ifBlank { video.attr("data-src") }
            if (!src.startsWith("http")) {
                if (msg.selectFirst(".tgme_widget_message_video_player") != null ||
                    msg.selectFirst(".message_media_not_supported") != null
                ) {
                    unpublished++
                }
                continue
            }
            val text = msg.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
            val fileName = msg.selectFirst(".tgme_widget_message_document_title")?.text()?.trim().orEmpty()
            val date = msg.selectFirst("time")?.attr("datetime")?.takeIf { it.isNotBlank() }
                ?: msg.selectFirst(".tgme_widget_message_date")?.text()?.trim().orEmpty()
            out.add(
                TelegramVideo(
                    channel = channel,
                    messageId = id,
                    title = listOf(text, fileName).firstOrNull { it.isNotBlank() }
                        ?: "Video $id",
                    url = src,
                    posterUrl = thumbnailOf(msg, video),
                    duration = msg.selectFirst(".tgme_widget_message_video_duration")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() },
                    dateLabel = friendlyDate(date),
                ),
            )
        }
        return TelegramPage(
            title = titleOf(html),
            videos = out,
            unpublished = unpublished,
            posts = posts.size,
        )
    }

    fun loadPost(channel: String, messageId: Long): TelegramPost? {
        val name = channel.removePrefix("@")
        val html = fetchHtml("$BASE/$name/$messageId?embed=1") ?: return null
        return parsePost(html, channel, messageId)
    }

    fun parsePost(html: String, channel: String, messageId: Long): TelegramPost {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return TelegramPost(null, null, false, null)
        val msg = doc.selectFirst("div.tgme_widget_message")
            ?: return fallbackPost(doc)
        val title = postTitleOf(msg)
        val videoEl = msg.selectFirst("video")
        val src = videoEl?.attr("src")?.ifBlank { videoEl.attr("data-src") }.orEmpty()
        val poster = thumbnailOf(msg, videoEl)
        if (src.startsWith("http")) {
            val durText = msg.selectFirst(".tgme_widget_message_video_duration")?.text()?.trim().orEmpty()
            return TelegramPost(
                title = title,
                video = TelegramVideo(
                    channel = channel,
                    messageId = messageId,
                    title = title ?: "Video $messageId",
                    url = src,
                    posterUrl = poster,
                    duration = durText.ifBlank { null },
                    dateLabel = null,
                ),
                withheld = false,
                posterUrl = poster,
            )
        }
        val withheld = msg.selectFirst(".tgme_widget_message_video_player") != null ||
            msg.selectFirst(".message_media_not_supported") != null
        return TelegramPost(title, null, withheld, poster)
    }

    private fun fallbackPost(doc: org.jsoup.nodes.Document): TelegramPost {
        val meta = doc.selectFirst("meta[property=\"og:description\"]")
        val descText = meta?.attr("content")?.trim().orEmpty()
        return TelegramPost(descText.ifEmpty { null }, null, false, null)
    }

    private fun postTitleOf(msg: Element): String? {
        val text = msg.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
        val fileName = msg.selectFirst(".tgme_widget_message_document_title")?.text()?.trim().orEmpty()
        return when {
            text.isNotBlank() -> text
            fileName.isNotBlank() -> fileName
            else -> null
        }
    }

    private fun thumbnailOf(msg: Element, video: Element?): String? {
        val posterAttr = video?.attr("poster").orEmpty()
        if (posterAttr.startsWith("http")) return posterAttr
        val selectors = listOf(
            ".tgme_widget_message_video_thumb",
            ".tgme_widget_message_photo_wrap",
            ".tgme_widget_message_video_thumb_wrap",
        )
        for (selector in selectors) {
            val style = msg.selectFirst(selector)?.attr("style").orEmpty()
            val url = backgroundUrl(style)
            if (url != null) return url
        }
        return null
    }

    private fun backgroundUrl(style: String): String? {
        val at = style.indexOf("url(")
        if (at < 0) return null
        return style.substring(at + "url(".length)
            .substringBefore(")")
            .trim()
            .trim('\'', '"')
            .takeIf { it.startsWith("http") }
    }

    private fun friendlyDate(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = raw.replace("T", " ").substringBefore("+").substringBefore("Z").trim()
        return if (cleaned.length >= DATE_PREFIX_LEN) cleaned.take(DATE_PREFIX_LEN) else cleaned
    }

    private fun fetchHtml(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", networkHelper.defaultUserAgentProvider())
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        networkHelper.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()
}
