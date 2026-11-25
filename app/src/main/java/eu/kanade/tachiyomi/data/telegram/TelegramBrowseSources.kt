package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Suppress("MagicNumber", "TooManyFunctions")
object TelegramBrowseSources {

    const val PKG_PREFIX = "telegram.source."
    private const val SOURCE_ID_PREFIX = 0x5447_0000_0000_0000L
    private const val SOURCE_ID_MASK = -0x1000000000000L
    private const val SOURCE_HASH_MASK = 0x0000_FFFF_FFFF_FFFFL
    private const val ICON_PX = 128
    private const val CORNER_RADIUS_PX = 28f
    private const val TELEGRAM_BLUE = 0xFF24A1DE.toInt()
    private const val AVATAR_TIMEOUT_MS = 10_000

    data class Entry(
        val key: String,
        val chatId: Long,
        val handle: String,
        val title: String,
        val kind: String,
        val photoFileId: Int,
        val avatarPath: String,
    ) {
        val sourceId: Long
            get() = SOURCE_ID_PREFIX or (key.hashCode().toLong() and SOURCE_HASH_MASK)

        val pkgName: String
            get() = "$PKG_PREFIX$key"
    }

    fun isTelegramSourceId(sourceId: Long): Boolean =
        (sourceId and SOURCE_ID_MASK) == SOURCE_ID_PREFIX

    fun isTelegramPkg(pkgName: String): Boolean =
        pkgName.startsWith(PKG_PREFIX)

    fun keyForChat(chatId: Long): String = "chat_$chatId"

    fun keyForHandle(handle: String): String = "pub_${handle.lowercase()}"

    fun parse(raw: String): List<Entry> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 4) return@mapNotNull null
                val key = parts[0]
                val chatId = parts[1].toLongOrNull() ?: 0L
                val handle = parts[2]
                val title = parts[3].ifBlank { handle.ifBlank { "Telegram" } }
                val kind = parts.getOrNull(4).orEmpty().ifBlank { "CHANNEL" }
                val photoFileId = parts.getOrNull(5)?.toIntOrNull() ?: 0
                val avatarPath = parts.getOrNull(6).orEmpty()
                Entry(
                    key = key,
                    chatId = chatId,
                    handle = handle,
                    title = title,
                    kind = kind,
                    photoFileId = photoFileId,
                    avatarPath = avatarPath,
                )
            }
            .distinctBy { it.key }
            .toList()

    fun encode(entries: List<Entry>): String =
        entries.distinctBy { it.key }
            .joinToString("\n") { e ->
                val cleanTitle = e.title.replace("\t", " ").replace("\n", " ").trim()
                listOf(
                    e.key,
                    e.chatId.toString(),
                    e.handle,
                    cleanTitle,
                    e.kind,
                    e.photoFileId.toString(),
                    e.avatarPath,
                ).joinToString("\t")
            }

    fun containsChat(raw: String, chatId: Long): Boolean =
        parse(raw).any { it.chatId == chatId && chatId != 0L }

    fun containsHandle(raw: String, handle: String): Boolean =
        parse(raw).any { it.handle.equals(handle, ignoreCase = true) && handle.isNotBlank() }

    suspend fun addOrUpdateChat(
        context: Context,
        prefs: TelegramPreferences,
        chat: Td.Chat,
    ): Entry {
        val key = keyForChat(chat.id)
        val avatar = Td.persistAvatar(context, key, chat.photoFileId).orEmpty()
        val entry = Entry(
            key = key,
            chatId = chat.id,
            handle = "",
            title = chat.title,
            kind = chat.kind.name,
            photoFileId = chat.photoFileId,
            avatarPath = avatar,
        )
        saveEntry(prefs, entry)
        return entry
    }

    suspend fun addOrUpdatePublicChannel(
        context: Context,
        prefs: TelegramPreferences,
        handle: String,
        title: String,
    ): Entry {
        val cleanHandle = handle.trim().removePrefix("@")
        val key = keyForHandle(cleanHandle)
        val chatId = if (Td.isSignedIn()) {
            Td.joinedChat(cleanHandle)?.first ?: Td.publicChatId(cleanHandle) ?: 0L
        } else {
            0L
        }
        val photoId = if (Td.isSignedIn()) Td.publicChatPhotoId(cleanHandle) else 0
        val tdAvatar = if (photoId != 0) Td.persistAvatar(context, key, photoId) else null
        val webAvatar = tdAvatar ?: fetchWebAvatar(context, key, cleanHandle)
        val entry = Entry(
            key = key,
            chatId = chatId,
            handle = cleanHandle,
            title = title.ifBlank { "@$cleanHandle" },
            kind = "CHANNEL",
            photoFileId = photoId,
            avatarPath = webAvatar.orEmpty(),
        )
        saveEntry(prefs, entry)
        return entry
    }

    private fun saveEntry(prefs: TelegramPreferences, entry: Entry) {
        val current = parse(prefs.browseSources().get()).toMutableList()
        val idx = current.indexOfFirst { it.key == entry.key }
        if (idx >= 0) {
            current[idx] = entry
        } else {
            current.add(0, entry)
        }
        prefs.browseSources().set(encode(current))
        runCatching {
            val sourcePrefs = Injekt.get<SourcePreferences>()
            val langs = sourcePrefs.enabledLanguages().get()
            if ("all" !in langs) {
                sourcePrefs.enabledLanguages().set(langs + "all")
            }
            val disabled = sourcePrefs.disabledSources().get()
            val idStr = entry.sourceId.toString()
            if (idStr in disabled) {
                sourcePrefs.disabledSources().set(disabled - idStr)
            }
        }
    }

    fun removeChat(prefs: TelegramPreferences, chatId: Long) {
        val next = parse(prefs.browseSources().get()).filterNot { it.chatId == chatId }
        prefs.browseSources().set(encode(next))
    }

    fun removeHandle(prefs: TelegramPreferences, handle: String) {
        val next = parse(prefs.browseSources().get())
            .filterNot { it.handle.equals(handle, ignoreCase = true) }
        prefs.browseSources().set(encode(next))
    }

    fun removeByPkgName(prefs: TelegramPreferences, pkgName: String) {
        val key = pkgName.removePrefix(PKG_PREFIX)
        val next = parse(prefs.browseSources().get()).filterNot { it.key == key }
        prefs.browseSources().set(encode(next))
    }

    fun toInstalledExtensions(context: Context, raw: String): List<Extension.Installed> =
        parse(raw).map { entry ->
            val source = TelegramCatalogueSource(entry)
            val drawable = loadDrawable(context, entry)
            val kindLabel = when (entry.kind) {
                "BOT" -> "Telegram Bot"
                "GROUP" -> "Telegram Group"
                "SAVED" -> "Saved Messages"
                "PRIVATE" -> "Telegram Chat"
                "SECRET" -> "Secret Chat"
                else -> "Telegram Channel"
            }
            Extension.Installed(
                name = entry.title,
                pkgName = entry.pkgName,
                versionName = kindLabel,
                versionCode = 1L,
                libVersion = 1.6,
                lang = "all",
                isNsfw = false,
                isTorrent = false,
                pkgFactory = null,
                sources = listOf(source),
                icon = drawable,
                hasUpdate = false,
                isObsolete = false,
                isShared = false,
                signatureHash = "telegram",
                repoUrl = null,
                author = "Telegram",
            )
        }

    fun getDrawableForSource(context: Context, sourceId: Long, raw: String): Drawable? {
        val entry = parse(raw).firstOrNull { it.sourceId == sourceId } ?: return null
        return loadDrawable(context, entry)
    }

    fun loadDrawable(context: Context, entry: Entry): Drawable {
        val fileCandidates = buildList {
            if (entry.avatarPath.isNotBlank()) add(File(entry.avatarPath))
            add(File(File(context.filesDir, "tdlib_avatars"), "${entry.key}.jpg"))
            if (entry.photoFileId != 0) {
                val tdPath = Td.fileState(entry.photoFileId)?.path.orEmpty()
                if (tdPath.isNotBlank()) add(File(tdPath))
            }
        }
        val existing = fileCandidates.firstOrNull { it.exists() && it.length() > 0L }
        if (existing != null) {
            val decoded = runCatching { BitmapFactory.decodeFile(existing.absolutePath) }.getOrNull()
            if (decoded != null) {
                return BitmapDrawable(context.resources, makeRoundedBitmap(decoded))
            }
        }
        return BitmapDrawable(context.resources, makeTelegramFallbackBitmap(context))
    }

    private fun makeRoundedBitmap(src: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, ICON_PX, ICON_PX, true)
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat())
        canvas.drawRoundRect(rect, CORNER_RADIUS_PX, CORNER_RADIUS_PX, paint)
        return out
    }

    private fun makeTelegramFallbackBitmap(context: Context): Bitmap {
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TELEGRAM_BLUE
        }
        val rect = RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat())
        canvas.drawRoundRect(rect, CORNER_RADIUS_PX, CORNER_RADIUS_PX, bgPaint)
        val vector = ContextCompat.getDrawable(context, R.drawable.ic_telegram_24dp)
        if (vector != null) {
            val pad = (ICON_PX * 0.22f).toInt()
            vector.setBounds(pad, pad, ICON_PX - pad, ICON_PX - pad)
            vector.draw(canvas)
        }
        return out
    }

    private suspend fun fetchWebAvatar(context: Context, key: String, handle: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageConn = (URL("https://t.me/$handle").openConnection() as HttpURLConnection).apply {
                    connectTimeout = AVATAR_TIMEOUT_MS
                    readTimeout = AVATAR_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                }
                val html = pageConn.inputStream.bufferedReader().use { it.readText() }
                val doc = Jsoup.parse(html)
                val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
                if (!imageUrl.startsWith("http")) return@runCatching null
                val imgConn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = AVATAR_TIMEOUT_MS
                    readTimeout = AVATAR_TIMEOUT_MS
                }
                val bytes = imgConn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@runCatching null
                val dir = File(context.filesDir, "tdlib_avatars").apply { mkdirs() }
                val dst = File(dir, "$key.jpg")
                dst.writeBytes(bytes)
                dst.absolutePath
            }.getOrNull()
        }
}
