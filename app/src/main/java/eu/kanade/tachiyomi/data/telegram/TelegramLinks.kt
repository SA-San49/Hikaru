package eu.kanade.tachiyomi.data.telegram

import eu.kanade.domain.telegram.TelegramPreferences
import org.json.JSONArray
import org.json.JSONObject

object TelegramLinks {

    private const val PRIVATE_LINK_MIN_PARTS = 3
    private val CHANNEL_REGEX = Regex("[A-Za-z0-9_]{3,64}")

    data class Link(
        val url: String,
        val channel: String,
        val messageId: Long,
        val title: String,
        val poster: String = "",
    )

    fun decode(raw: String): List<Link> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").trim()
                val channel = obj.optString("channel").trim()
                val id = obj.optLong("id", 0L)
                if (url.isBlank() || id <= 0L) return@mapNotNull null
                Link(
                    url = url,
                    channel = channel,
                    messageId = id,
                    title = obj.optString("title").trim(),
                    poster = obj.optString("poster").trim(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encode(list: List<Link>): String {
        val arr = JSONArray()
        for (link in list) {
            arr.put(
                JSONObject()
                    .put("url", link.url)
                    .put("channel", link.channel)
                    .put("id", link.messageId)
                    .put("title", link.title)
                    .put("poster", link.poster),
            )
        }
        return arr.toString()
    }

    fun add(prefs: TelegramPreferences, existing: List<Link>, link: Link) {
        if (existing.any { it.url.equals(link.url, ignoreCase = true) }) return
        prefs.links().set(encode(existing + link))
    }

    fun remove(prefs: TelegramPreferences, existing: List<Link>, url: String) {
        prefs.links().set(encode(existing.filterNot { it.url.equals(url, ignoreCase = true) }))
    }

    suspend fun describe(link: Link): Link {
        if (Td.isSignedIn()) {
            val video = runCatching { Td.linkVideo(link.url) }.getOrNull()
            if (video != null) {
                return link.copy(title = video.title.trim().ifBlank { link.title })
            }
        }
        val post = runCatching { TelegramWeb.loadPost(link.channel, link.messageId) }.getOrNull()
            ?: return link
        return link.copy(
            title = post.title?.trim()?.takeIf { it.isNotBlank() } ?: link.title,
            poster = post.posterUrl.orEmpty().ifBlank { link.poster },
        )
    }

    @Suppress("ReturnCount")
    fun normalize(raw: String): Link? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("tg://", ignoreCase = true)) {
            val params = text.substringAfter("?", "")
                .split("&")
                .mapNotNull { part ->
                    val at = part.indexOf("=")
                    if (at <= 0) null
                    else part.substring(0, at).lowercase() to part.substring(at + 1)
                }
                .toMap()
            val name = params["domain"] ?: return null
            val post = params["post"]?.toLongOrNull() ?: return null
            return build(name, post)
        }
        var s = text
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("t.me/").removePrefix("telegram.me/").removePrefix("telegram.dog/")
        s = s.substringBefore("?").substringBefore("#")
        var parts = s.split("/").filter { it.isNotBlank() }
        if (parts.isNotEmpty() && parts[0].equals("s", ignoreCase = true)) parts = parts.drop(1)
        if (parts.isEmpty()) return null
        if (parts.size >= PRIVATE_LINK_MIN_PARTS && parts[0].equals("c", ignoreCase = true)) {
            val post = parts[2].toLongOrNull() ?: return null
            val internal = parts[1].filter { it.isDigit() }
            if (internal.isEmpty()) return null
            return build("c/$internal", post)
        }
        if (parts.size < 2) return null
        val post = parts[1].toLongOrNull() ?: return null
        return build(parts[0], post)
    }

    @Suppress("ReturnCount")
    private fun build(channelRaw: String, post: Long): Link? {
        if (post <= 0) return null
        val channel = channelRaw.trim().removePrefix("@")
        if (channel.startsWith("c/")) {
            val internal = channel.removePrefix("c/")
            if (internal.isEmpty() || !internal.all { it.isDigit() }) return null
            return Link("https://t.me/c/$internal/$post", "c/$internal", post, "")
        }
        if (!channel.matches(CHANNEL_REGEX)) return null
        return Link("https://t.me/$channel/$post", "@$channel", post, "")
    }
}
