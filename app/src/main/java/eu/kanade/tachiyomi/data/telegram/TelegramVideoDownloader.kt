package eu.kanade.tachiyomi.data.telegram

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object TelegramVideoDownloader {

    enum class Status {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        FAILED,
    }

    data class DownloadItemState(
        val key: String,
        val title: String,
        val status: Status,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private const val MAX_CONCURRENT_DOWNLOADS = 2
    private const val TD_DOWNLOAD_PRIORITY = 32
    private const val TD_POLL_TIMEOUT_MS = 250L
    private const val TD_STALL_TIMEOUT_MS = 120_000L
    private const val RETRY_DELAY_MS = 400L
    private const val COPY_BUFFER_BYTES = 65_536
    private const val PROGRESS_STEP_BYTES = 262_144L
    private const val MAX_ACTIVE_PROGRESS = 0.99f
    private const val FULL_PROGRESS = 1f
    private const val MAX_UNIQUE_ATTEMPTS = 99
    private const val MIN_EXT_LENGTH = 2
    private const val MAX_EXT_LENGTH = 5
    private val ALLOWED_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val storageManager by lazy { Injekt.get<StorageManager>() }
    private val networkHelper by lazy { Injekt.get<NetworkHelper>() }

    private val _states = MutableStateFlow<Map<String, DownloadItemState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadItemState>> = _states.asStateFlow()

    fun keyForTd(chatId: Long, messageId: Long): String = "td:$chatId:$messageId"

    fun keyForWeb(channel: String, messageId: Long): String = "web:$channel:$messageId"

    fun enqueueTd(context: Context, channelTitle: String, videos: List<Td.ChatVideo>) {
        if (videos.isEmpty()) return
        val appContext = context.applicationContext
        val toQueue = videos.filter { video ->
            val key = keyForTd(video.chatId, video.messageId)
            val current = _states.value[key]?.status
            current != Status.QUEUED && current != Status.DOWNLOADING
        }
        if (toQueue.isEmpty()) {
            context.toast("Already downloading")
            return
        }
        toQueue.forEach { video ->
            val key = keyForTd(video.chatId, video.messageId)
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.QUEUED,
                    progress = 0f,
                ),
            )
        }
        context.toast(
            if (toQueue.size == 1) {
                "Downloading ${toQueue.first().title}"
            } else {
                "Downloading ${toQueue.size} videos"
            },
        )
        toQueue.forEach { video ->
            scope.launch {
                semaphore.withPermit {
                    runTdDownload(appContext, channelTitle, video)
                }
            }
        }
    }

    fun enqueueWeb(context: Context, channelTitle: String, videos: List<TelegramVideo>) {
        if (videos.isEmpty()) return
        val appContext = context.applicationContext
        val toQueue = videos.filter { video ->
            val key = keyForWeb(video.channel, video.messageId)
            val current = _states.value[key]?.status
            current != Status.QUEUED && current != Status.DOWNLOADING
        }
        if (toQueue.isEmpty()) {
            context.toast("Already downloading")
            return
        }
        toQueue.forEach { video ->
            val key = keyForWeb(video.channel, video.messageId)
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.QUEUED,
                    progress = 0f,
                ),
            )
        }
        context.toast(
            if (toQueue.size == 1) {
                "Downloading ${toQueue.first().title}"
            } else {
                "Downloading ${toQueue.size} videos"
            },
        )
        toQueue.forEach { video ->
            scope.launch {
                semaphore.withPermit {
                    runWebDownload(appContext, channelTitle, video)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTdDownload(
        context: Context,
        channelTitle: String,
        video: Td.ChatVideo,
    ) {
        val key = keyForTd(video.chatId, video.messageId)
        var heldFileId = 0
        try {
            setState(DownloadItemState(key = key, title = video.title, status = Status.DOWNLOADING))
            val resolved = resolveTdFile(video)
            heldFileId = resolved.first
            Td.holdFileDownload(heldFileId)
            Td.clearDownloadError(heldFileId)
            val sourceFile = waitForTdFileComplete(key, video, heldFileId, resolved.second)
            val targetName = buildTargetFilename(
                preferredName = video.fileName,
                fallbackTitle = video.title,
                sourcePath = sourceFile.name,
                fallbackUrl = null,
            )
            saveStreamToDownloads(
                context = context,
                channelTitle = channelTitle,
                fileName = targetName,
                totalBytes = sourceFile.length(),
                onProgress = { ratio ->
                    setState(
                        DownloadItemState(
                            key = key,
                            title = video.title,
                            status = Status.DOWNLOADING,
                            progress = ratio,
                        ),
                    )
                },
            ) { output ->
                FileInputStream(sourceFile).use { input ->
                    copyWithProgress(input, output, sourceFile.length()) { ratio ->
                        setState(
                            DownloadItemState(
                                key = key,
                                title = video.title,
                                status = Status.DOWNLOADING,
                                progress = ratio,
                            ),
                        )
                    }
                }
            }
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.COMPLETED,
                    progress = FULL_PROGRESS,
                ),
            )
            withUIContext { context.toast("Saved $targetName") }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Telegram video download failed: ${video.title}" }
            val msg = e.message ?: "Download failed"
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.FAILED,
                    error = msg,
                ),
            )
            withUIContext { context.toast("Download failed: ${video.title}") }
        } finally {
            if (heldFileId != 0) {
                Td.releaseFileDownload(heldFileId)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runWebDownload(
        context: Context,
        channelTitle: String,
        video: TelegramVideo,
    ) {
        val key = keyForWeb(video.channel, video.messageId)
        try {
            setState(DownloadItemState(key = key, title = video.title, status = Status.DOWNLOADING))
            val req = Request.Builder()
                .url(video.url)
                .header("User-Agent", networkHelper.defaultUserAgentProvider())
                .header("Referer", "https://t.me/")
                .build()
            var targetName = ""
            networkHelper.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                targetName = buildTargetFilename(
                    preferredName = "",
                    fallbackTitle = video.title,
                    sourcePath = null,
                    fallbackUrl = video.url,
                )
                saveStreamToDownloads(
                    context = context,
                    channelTitle = channelTitle,
                    fileName = targetName,
                    totalBytes = totalBytes,
                    onProgress = {},
                ) { output ->
                    body.byteStream().use { input ->
                        copyWithProgress(input, output, totalBytes) { ratio ->
                            setState(
                                DownloadItemState(
                                    key = key,
                                    title = video.title,
                                    status = Status.DOWNLOADING,
                                    progress = ratio,
                                ),
                            )
                        }
                    }
                }
            }
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.COMPLETED,
                    progress = FULL_PROGRESS,
                ),
            )
            withUIContext { context.toast("Saved $targetName") }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Telegram web video download failed: ${video.url}" }
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.FAILED,
                    error = e.message ?: "Download failed",
                ),
            )
            withUIContext { context.toast("Download failed: ${video.title}") }
        }
    }

    private suspend fun resolveTdFile(video: Td.ChatVideo): Pair<Int, Long> {
        var fileId = video.fileId
        var knownSize = video.size
        var state = if (fileId != 0) Td.stateOf(fileId) else null
        if (fileId == 0 || state == null || !state.canBeDownloaded) {
            val fresh = Td.chatVideoById(video.chatId, video.messageId)
            if (fresh != null && fresh.fileId != 0) {
                fileId = fresh.fileId
                if (fresh.size > 0L) knownSize = fresh.size
                state = Td.stateOf(fileId)
            }
        }
        if (fileId == 0 || state == null || !state.canBeDownloaded) {
            val touched = Td.touchMessage(video.chatId, video.messageId)
            if (touched != 0) {
                fileId = touched
                state = Td.stateOf(fileId)
            }
        }
        if (fileId == 0) throw IOException("Telegram file unavailable")
        val finalSize = maxOf(knownSize, state?.size ?: 0L)
        return fileId to finalSize
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun waitForTdFileComplete(
        key: String,
        video: Td.ChatVideo,
        fileId: Int,
        expectedSize: Long,
    ): File {
        var state = Td.stateOf(fileId)
        val initialFile = state?.path?.takeIf { it.isNotBlank() }?.let(::File)
        if (state?.complete == true && initialFile != null && initialFile.exists()) {
            return initialFile
        }
        Td.request(fileId, 0L, 0L, TD_DOWNLOAD_PRIORITY)
        var lastProgress = -1L
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            val rev = Td.revision.value
            state = Td.fileState(fileId) ?: Td.stateOf(fileId)
            val err = Td.downloadError(fileId)
            if (!err.isNullOrBlank()) {
                delay(RETRY_DELAY_MS)
                Td.clearDownloadError(fileId)
                Td.request(fileId, 0L, 0L, TD_DOWNLOAD_PRIORITY)
            }
            val total = maxOf(expectedSize, state?.size ?: 0L, 1L)
            val ready = state?.readyFromOffset ?: 0L
            val path = state?.path.orEmpty()
            val file = path.takeIf { it.isNotBlank() }?.let(::File)
            val ratio = (ready.toFloat() / total.toFloat()).coerceIn(0f, MAX_ACTIVE_PROGRESS)
            setState(
                DownloadItemState(
                    key = key,
                    title = video.title,
                    status = Status.DOWNLOADING,
                    progress = ratio,
                ),
            )
            if (state?.complete == true && file != null && file.exists()) {
                return file
            }
            if (ready != lastProgress) {
                lastProgress = ready
                lastProgressAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgressAt > TD_STALL_TIMEOUT_MS) {
                throw IOException("Telegram download timed out")
            }
            if (state?.active != true && state?.complete != true) {
                Td.request(fileId, 0L, 0L, TD_DOWNLOAD_PRIORITY)
            }
            Td.awaitUpdate(rev, TD_POLL_TIMEOUT_MS)
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var nextReportAt = PROGRESS_STEP_BYTES
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            copied += read
            if (totalBytes > 0L && copied >= nextReportAt) {
                onProgress((copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, MAX_ACTIVE_PROGRESS))
                nextReportAt = copied + PROGRESS_STEP_BYTES
            }
        }
        output.flush()
    }

    @Suppress("ReturnCount", "LongParameterList")
    private fun saveStreamToDownloads(
        context: Context,
        channelTitle: String,
        fileName: String,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
        writer: (OutputStream) -> Unit,
    ) {
        val safeChannel = DiskUtil.buildValidFilename(channelTitle.ifBlank { "Telegram" })
        val baseDownloads = storageManager.getDownloadsDirectory()
        if (baseDownloads != null && baseDownloads.exists()) {
            val tgDir = baseDownloads.createDirectory("Telegram") ?: baseDownloads
            val chanDir = tgDir.createDirectory(safeChannel) ?: tgDir
            val uniqueName = uniqueUniFilename(chanDir, fileName)
            val target = chanDir.createFile(uniqueName)
                ?: throw IOException("Cannot create download file: $uniqueName")
            target.openOutputStream().use(writer)
            onProgress(FULL_PROGRESS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFromName(fileName))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Hikaru/Telegram/$safeChannel",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (totalBytes > 0L) {
                    put(MediaStore.MediaColumns.SIZE, totalBytes)
                }
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create MediaStore download entry")
            try {
                resolver.openOutputStream(uri)?.use(writer)
                    ?: throw IOException("Cannot open download output stream")
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                onProgress(FULL_PROGRESS)
                return
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(publicRoot, "Hikaru/Telegram/$safeChannel")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create download directory")
        }
        val outFile = uniqueLocalFile(dir, fileName)
        outFile.outputStream().use(writer)
        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
        onProgress(FULL_PROGRESS)
    }

    @Suppress("ReturnCount")
    private fun uniqueUniFilename(dir: UniFile, rawName: String): String {
        if (dir.findFile(rawName) == null) return rawName
        val dot = rawName.lastIndexOf('.')
        val stem = if (dot > 0) rawName.substring(0, dot) else rawName
        val ext = if (dot > 0) rawName.substring(dot) else ""
        for (i in 1..MAX_UNIQUE_ATTEMPTS) {
            val candidate = "$stem ($i)$ext"
            if (dir.findFile(candidate) == null) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    @Suppress("ReturnCount")
    private fun uniqueLocalFile(dir: File, rawName: String): File {
        val first = File(dir, rawName)
        if (!first.exists()) return first
        val dot = rawName.lastIndexOf('.')
        val stem = if (dot > 0) rawName.substring(0, dot) else rawName
        val ext = if (dot > 0) rawName.substring(dot) else ""
        for (i in 1..MAX_UNIQUE_ATTEMPTS) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "${stem}_${System.currentTimeMillis()}$ext")
    }

    private fun buildTargetFilename(
        preferredName: String,
        fallbackTitle: String,
        sourcePath: String?,
        fallbackUrl: String?,
    ): String {
        val raw = preferredName.trim().ifBlank { fallbackTitle.trim().ifBlank { "video" } }
        val ext = extractExtension(preferredName)
            ?: extractExtension(sourcePath)
            ?: extractExtension(fallbackUrl?.substringBefore('?'))
            ?: "mp4"
        val clean = DiskUtil.buildValidFilename(raw)
        val hasExt = clean.substringAfterLast('.', "").lowercase() in ALLOWED_EXTENSIONS
        return if (hasExt) clean else "$clean.$ext"
    }

    @Suppress("ReturnCount")
    private fun extractExtension(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val candidate = name.substringAfterLast('/', name).substringAfterLast('.', "").lowercase()
        if (candidate.length !in MIN_EXT_LENGTH..MAX_EXT_LENGTH) return null
        return candidate.takeIf { it in ALLOWED_EXTENSIONS }
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private fun setState(state: DownloadItemState) {
        _states.update { current -> current + (state.key to state) }
    }
}
