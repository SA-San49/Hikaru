package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import eu.kanade.tachiyomi.util.storage.toFFmpegReadString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import tachiyomi.source.localanime.LocalAnimeSource
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt
import coil3.Uri as CoilUri

/**
 * Lazily extracts and disk-caches thumbnails for local video episodes on demand.
 * Only visible episodes trigger frame extraction, keeping episode list loading instant
 * and bounding CPU/RAM pressure on low-end devices.
 */
class LocalVideoThumbnailFetcher(
    private val context: Context,
    private val requestUri: CoilUri,
    private val cacheDir: File,
    private val decodeSemaphore: Semaphore,
    private val maxCacheBytes: Long,
    private val failedKeys: LruCache<String, Boolean>,
    private val inFlight: ConcurrentHashMap<String, CompletableDeferred<Boolean>>,
    private val writeCounter: AtomicInteger,
    private val fileSystem: LocalAnimeSourceFileSystem,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(requestUri.toString())
        val animeUrl = parsed.getQueryParameter("anime")
            ?: throw IOException("Missing anime parameter in localvideo URI")
        val episodeRelativePath = parsed.getQueryParameter("ep")
            ?: throw IOException("Missing ep parameter in localvideo URI")
        val lastModified = parsed.getQueryParameter("lm").orEmpty()

        val cacheKey = sha256Hex("$animeUrl|$episodeRelativePath|$lastModified")
        val cacheFile = File(cacheDir, "$cacheKey.jpg")

        if (cacheFile.isFile && cacheFile.length() > 0L) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return@withContext createResult(cacheFile)
        }

        if (failedKeys.get(cacheKey) == true) {
            throw IOException("Previously failed local video thumbnail: $episodeRelativePath")
        }

        val existingDeferred = inFlight[cacheKey]
        if (existingDeferred != null) {
            val succeeded = existingDeferred.await()
            if (succeeded && cacheFile.isFile && cacheFile.length() > 0L) {
                return@withContext createResult(cacheFile)
            }
            throw IOException("Failed to extract local video thumbnail: $episodeRelativePath")
        }

        val deferred = CompletableDeferred<Boolean>()
        val raceWinner = inFlight.putIfAbsent(cacheKey, deferred)
        if (raceWinner != null) {
            val succeeded = raceWinner.await()
            if (succeeded && cacheFile.isFile && cacheFile.length() > 0L) {
                return@withContext createResult(cacheFile)
            }
            throw IOException("Failed to extract local video thumbnail: $episodeRelativePath")
        }

        var succeeded = false
        try {
            currentCoroutineContext().ensureActive()
            decodeSemaphore.withPermit {
                currentCoroutineContext().ensureActive()
                if (cacheFile.isFile && cacheFile.length() > 0L) {
                    succeeded = true
                    return@withPermit
                }
                val entry = fileSystem.findEpisodeEntry(animeUrl, episodeRelativePath)
                    ?: return@withPermit
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                succeeded = extractWithRetriever(entry.uri, cacheFile) ||
                    extractWithFFmpeg(entry.uri, cacheFile)
                if (succeeded) {
                    if (writeCounter.incrementAndGet() % EVICT_CHECK_INTERVAL == 0) {
                        trimCacheIfNeeded()
                    }
                } else {
                    failedKeys.put(cacheKey, true)
                }
            }
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (_: Exception) {
            failedKeys.put(cacheKey, true)
        } finally {
            if (!deferred.isCompleted) {
                deferred.complete(succeeded)
            }
            inFlight.remove(cacheKey, deferred)
        }

        if (succeeded && cacheFile.isFile && cacheFile.length() > 0L) {
            createResult(cacheFile)
        } else {
            throw IOException("Unable to extract local video thumbnail: $episodeRelativePath")
        }
    }

    private fun createResult(cacheFile: File): SourceFetchResult {
        return SourceFetchResult(
            source = ImageSource(
                file = cacheFile.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private fun extractWithRetriever(videoUri: Uri, targetFile: File): Boolean {
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        val tmpFile = File(cacheDir, "${targetFile.name}.tmp")
        return try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val seekMs = if (durationMs > 0L) {
                min(durationMs / SEEK_FRACTION, MAX_SEEK_MS)
            } else {
                FALLBACK_SEEK_MS
            }
            val seekUs = seekMs * US_PER_MS

            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    seekUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_MAX_WIDTH,
                    THUMB_MAX_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            val raw = bitmap ?: return false
            val outputBitmap = if (raw.width > THUMB_MAX_WIDTH || raw.height > THUMB_MAX_HEIGHT) {
                val scale = min(
                    THUMB_MAX_WIDTH.toFloat() / raw.width.toFloat(),
                    THUMB_MAX_HEIGHT.toFloat() / raw.height.toFloat(),
                )
                val w = (raw.width * scale).roundToInt().coerceAtLeast(1)
                val h = (raw.height * scale).roundToInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(raw, w, h, true)
                scaled
            } else {
                raw
            }

            FileOutputStream(tmpFile).use { fos ->
                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, fos)) {
                    return false
                }
            }
            if (tmpFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile) || (tmpFile.copyTo(targetFile, overwrite = true).length() > 0L)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
            scaled?.recycle()
            if (bitmap !== scaled) {
                bitmap?.recycle()
            }
            runCatching { retriever.release() }
        }
    }

    private fun extractWithFFmpeg(videoUri: Uri, targetFile: File): Boolean {
        val readParam = videoUri.toFFmpegReadString(context) ?: return false
        val tmpFile = File(cacheDir, "${targetFile.name}.ff.jpg")
        return try {
            val outPath = tmpFile.absolutePath.replace("\"", "\\\"")
            val primaryCmd = "-noaccurate_seek -ss $FFMPEG_SEEK_SEC -i \"$readParam\" " +
                "-frames:v 1 -vf scale=$THUMB_MAX_WIDTH:-2 -q:v $FFMPEG_QUALITY -y \"$outPath\""
            val session = FFmpegKit.execute(primaryCmd)
            if (!ReturnCode.isSuccess(session.returnCode) || tmpFile.length() == 0L) {
                val fallbackCmd = "-noaccurate_seek -ss 1 -i \"$readParam\" " +
                    "-frames:v 1 -vf scale=$THUMB_MAX_WIDTH:-2 -q:v $FFMPEG_QUALITY -y \"$outPath\""
                FFmpegKit.execute(fallbackCmd)
            }
            if (tmpFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile) || (tmpFile.copyTo(targetFile, overwrite = true).length() > 0L)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalBytes = 0L
        for (file in files) {
            totalBytes += file.length()
        }
        if (totalBytes <= maxCacheBytes) return

        val targetBytes = (maxCacheBytes * EVICT_TARGET_RATIO).toLong()
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (totalBytes <= targetBytes) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
            }
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and HEX_BYTE_MASK
            chars[i * 2] = HEX_DIGITS[v ushr HEX_SHIFT]
            chars[i * 2 + 1] = HEX_DIGITS[v and HEX_LOW_NIBBLE]
        }
        return String(chars)
    }

    class Factory(
        private val context: Context,
        isLowEndDevice: Boolean,
    ) : Fetcher.Factory<CoilUri> {

        private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        private val decodeSemaphore = Semaphore(if (isLowEndDevice) 1 else DEFAULT_DECODE_CONCURRENCY)
        private val maxCacheBytes = if (isLowEndDevice) LOW_RAM_CACHE_BYTES else DEFAULT_CACHE_BYTES
        private val failedKeys = LruCache<String, Boolean>(NEGATIVE_CACHE_SIZE)
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
        private val writeCounter = AtomicInteger(0)
        private val fileSystem: LocalAnimeSourceFileSystem by lazy { Injekt.get() }

        override fun create(
            data: CoilUri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.scheme != LocalAnimeSource.LOCAL_VIDEO_THUMB_SCHEME) {
                return null
            }
            return LocalVideoThumbnailFetcher(
                context = context,
                requestUri = data,
                cacheDir = cacheDir,
                decodeSemaphore = decodeSemaphore,
                maxCacheBytes = maxCacheBytes,
                failedKeys = failedKeys,
                inFlight = inFlight,
                writeCounter = writeCounter,
                fileSystem = fileSystem,
            )
        }
    }

    companion object {
        private const val CACHE_DIR_NAME = "local_video_thumbs"
        private const val THUMB_MAX_WIDTH = 480
        private const val THUMB_MAX_HEIGHT = 270
        private const val THUMB_JPEG_QUALITY = 80
        private const val SEEK_FRACTION = 4L
        private const val MAX_SEEK_MS = 10_000L
        private const val FALLBACK_SEEK_MS = 2_000L
        private const val US_PER_MS = 1_000L
        private const val FFMPEG_SEEK_SEC = 10
        private const val FFMPEG_QUALITY = 5
        private const val DEFAULT_DECODE_CONCURRENCY = 2
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val LOW_RAM_CACHE_BYTES = 20L * BYTES_PER_MB
        private const val DEFAULT_CACHE_BYTES = 40L * BYTES_PER_MB
        private const val EVICT_CHECK_INTERVAL = 24
        private const val EVICT_TARGET_RATIO = 0.8
        private const val NEGATIVE_CACHE_SIZE = 256
        private const val HEX_BYTE_MASK = 0xff
        private const val HEX_SHIFT = 4
        private const val HEX_LOW_NIBBLE = 0x0f
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
