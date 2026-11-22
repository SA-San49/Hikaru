package tachiyomi.source.localanime

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.toFFmpegReadString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import rx.Observable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.core.metadata.tachiyomi.EpisodeDetails
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.episode.service.EpisodeRecognition
import tachiyomi.i18n.MR
import tachiyomi.source.localanime.filter.OrderBy
import tachiyomi.source.localanime.image.LocalAnimeSourceCoverManager
import tachiyomi.source.localanime.image.LocalAnimeSourceEpisodeThumbnailManager
import tachiyomi.source.localanime.io.Archive
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import tachiyomi.source.localanime.io.LocalFileEntry
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import tachiyomi.domain.source.model.Source as DomainSource

actual class LocalAnimeSource(
    private val context: Context,
    private val fileSystem: LocalAnimeSourceFileSystem,
    private val coverManager: LocalAnimeSourceCoverManager,
    private val thumbnailManager: LocalAnimeSourceEpisodeThumbnailManager,
) : CatalogueSource, UnmeteredSource {

    private val json: Json by injectLazy()
    private val animeRepository: AnimeRepository by injectLazy()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coverFetchSemaphore = Semaphore(COVER_FETCH_CONCURRENCY)
    private val coverGenSemaphore = Semaphore(1)

    private val coverUriCache = ConcurrentHashMap<String, String>()
    private val coverGenJobs = ConcurrentHashMap<String, Job>()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name = context.stringResource(MR.strings.local_anime_source)

    override val id: Long = ID

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

    fun invalidateCache(animeUrl: String? = null) {
        if (animeUrl == null) {
            coverUriCache.clear()
            fileSystem.invalidateCache()
        } else {
            coverUriCache.remove(animeUrl)
            fileSystem.invalidateAnimeCache(animeUrl)
        }
    }

    // Browse related
    override suspend fun getPopularAnime(page: Int) = getSearchAnime(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchAnime(page, "", LatestFilters)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: FilterList,
    ): AnimesPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        val allEntries = fileSystem.listBaseDirectoryEntries()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .distinctBy { it.name }

        if (coverUriCache.size > allEntries.size * COVER_CACHE_PRUNE_FACTOR) {
            val validNames = allEntries.mapTo(HashSet(allEntries.size)) { it.name }
            coverUriCache.keys.retainAll(validNames)
        }

        var filteredEntries = allEntries.filter { entry ->
            if (lastModifiedLimit == 0L && query.isBlank()) {
                true
            } else if (lastModifiedLimit == 0L) {
                entry.name.contains(query, ignoreCase = true)
            } else {
                entry.lastModified >= lastModifiedLimit
            }
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    filteredEntries = if (filter.state?.ascending != false) {
                        filteredEntries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    } else {
                        filteredEntries.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name },
                        )
                    }
                }

                is OrderBy.Latest -> {
                    filteredEntries = if (filter.state?.ascending == true) {
                        filteredEntries.sortedBy { it.lastModified }
                    } else {
                        filteredEntries.sortedByDescending { it.lastModified }
                    }
                }

                else -> {
                    /* Do nothing */
                }
            }
        }

        // Paginate results lazily so 1000+ local anime load incrementally without blocking SAF/IO threads
        val safePage = page.coerceAtLeast(1)
        val fromIndex = (safePage - 1) * PAGE_SIZE
        if (fromIndex >= filteredEntries.size) {
            return@withIOContext AnimesPage(emptyList(), false)
        }
        val toIndex = minOf(fromIndex + PAGE_SIZE, filteredEntries.size)
        val pageSlice = filteredEntries.subList(fromIndex, toIndex)
        val hasNextPage = toIndex < filteredEntries.size

        // Resolve covers only for the active page slice with bounded concurrency
        val animes = pageSlice
            .map { entry ->
                async {
                    val coverUri = coverUriCache[entry.name] ?: coverFetchSemaphore.withPermit {
                        coverUriCache[entry.name] ?: run {
                            val foundUri = fileSystem.findCoverUriInDirectory(entry.name, entry.uri)
                                ?.toString()
                                .orEmpty()
                            coverUriCache[entry.name] = foundUri
                            foundUri
                        }
                    }

                    SAnime.create().apply {
                        title = entry.name
                        url = entry.name
                        if (coverUri.isNotEmpty()) {
                            thumbnail_url = coverUri
                        }
                    }
                }
            }
            .awaitAll()

        AnimesPage(animes, hasNextPage)
    }

    // Old fetch functions

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopular"))
    override fun fetchPopularAnime(page: Int) = fetchSearchAnime(page, "", PopularFilters)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = fetchSearchAnime(page, "", LatestFilters)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: FilterList): Observable<AnimesPage> {
        return runBlocking {
            Observable.just(getSearchAnime(page, query, filters))
        }
    }

    fun updateAnimeInfo(anime: SAnime) {
        val directory = fileSystem.getFilesInBaseDirectory().map { File(it.filePath, anime.url) }.find {
            it.exists()
        } ?: return
        val existingFileName = directory.listFiles()?.find { it.extension == "json" }?.name
        val file = File(directory, existingFileName ?: "info.json")
        file.outputStream().use {
            json.encodeToStream(anime.toJson(), it)
        }
    }

    private fun SAnime.toJson(): AnimeDetails {
        return AnimeDetails(title, author, artist, description, genre?.split(", "), status)
    }

    // Anime details related
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withIOContext {
        val entries = fileSystem.listAnimeDirectoryEntries(anime.url)

        entries.firstOrNull { !it.isDirectory && it.name.lowercase() in LocalAnimeSourceFileSystem.COVER_FILENAMES }
            ?.let { coverEntry ->
                val coverUri = coverEntry.uri.toString()
                anime.thumbnail_url = coverUri
                coverUriCache[anime.url] = coverUri
            }

        entries
            .firstOrNull {
                !it.isDirectory &&
                    it.extension.equals("json", ignoreCase = true) &&
                    it.nameWithoutExtension.equals("details", ignoreCase = true)
            }
            ?.let { detailsEntry ->
                runCatching {
                    context.contentResolver.openInputStream(detailsEntry.uri)?.use { stream ->
                        json.decodeFromStream<AnimeDetails>(stream)
                    }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to parse details.json for ${anime.url}" }
                }.getOrNull()?.run {
                    title?.let { anime.title = it }
                    author?.let { anime.author = it }
                    artist?.let { anime.artist = it }
                    description?.let { anime.description = it }
                    genre?.let { anime.genre = it.joinToString() }
                    status?.let { anime.status = it }
                }
            }

        return@withIOContext anime
    }

    // Episodes
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withIOContext {
        val topEntries = fileSystem.listAnimeDirectoryEntries(anime.url)
        if (topEntries.isEmpty()) return@withIOContext emptyList()

        val episodesData = topEntries
            .firstOrNull {
                !it.isDirectory &&
                    it.extension.equals("json", ignoreCase = true) &&
                    it.nameWithoutExtension.equals("episodes", ignoreCase = true)
            }
            ?.let { jsonEntry ->
                runCatching {
                    context.contentResolver.openInputStream(jsonEntry.uri)?.use { stream ->
                        json.decodeFromStream<List<EpisodeDetails>>(stream)
                    }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to parse episodes.json for ${anime.url}" }
                }.getOrNull()
            }

        val episodeDataByIntNumber = episodesData?.associateBy {
            (it.episode_number * EPISODE_KEY_SCALE).roundToInt()
        }

        val topImageLookup = thumbnailManager.buildImageLookup(topEntries)
        val rawEpisodes = ArrayList<SEpisode>(topEntries.size)
        var firstVideoEntry: LocalFileEntry? = null

        for (entry in topEntries) {
            if (!entry.isDirectory && Archive.isSupportedExtension(entry.extension)) {
                if (firstVideoEntry == null && Archive.isDirectVideoExtension(entry.extension)) {
                    firstVideoEntry = entry
                }
                rawEpisodes.add(
                    createEpisode(
                        anime = anime,
                        entry = entry,
                        relativePath = entry.name,
                        displayName = entry.nameWithoutExtension,
                        imageLookup = topImageLookup,
                        episodesData = episodesData,
                        episodeDataByIntNumber = episodeDataByIntNumber,
                    ),
                )
            }
        }

        // Also scan 1-level season subfolders without blocking on hidden folders
        for (entry in topEntries) {
            if (!entry.isDirectory || entry.name.startsWith('.')) continue
            val subEntries = fileSystem.listSubDirectoryEntries(anime.url, entry)
            if (subEntries.isEmpty()) continue
            val subImageLookup = thumbnailManager.buildImageLookup(subEntries)
            for (subEntry in subEntries) {
                if (!subEntry.isDirectory && Archive.isSupportedExtension(subEntry.extension)) {
                    if (firstVideoEntry == null && Archive.isDirectVideoExtension(subEntry.extension)) {
                        firstVideoEntry = subEntry
                    }
                    val relativePath = "${entry.name}/${subEntry.name}"
                    val displayName = "${entry.name} - ${subEntry.nameWithoutExtension}"
                    rawEpisodes.add(
                        createEpisode(
                            anime = anime,
                            entry = subEntry,
                            relativePath = relativePath,
                            displayName = displayName,
                            imageLookup = subImageLookup.ifEmpty { topImageLookup },
                            episodesData = episodesData,
                            episodeDataByIntNumber = episodeDataByIntNumber,
                        ),
                    )
                }
            }
        }

        val episodes = rawEpisodes.sortedWith { e1, e2 ->
            val cmp = e2.episode_number.compareTo(e1.episode_number)
            if (cmp == 0) e2.name.compareToCaseInsensitiveNaturalOrder(e1.name) else cmp
        }

        // Generate missing anime cover asynchronously (deduplicated, never blocks episode list)
        val hasExistingCover = anime.thumbnail_url != null ||
            topEntries.any { !it.isDirectory && it.name.lowercase() in LocalAnimeSourceFileSystem.COVER_FILENAMES }
        if (!hasExistingCover && firstVideoEntry != null) {
            scheduleCoverGeneration(anime.url, anime.title, firstVideoEntry)
        }

        episodes
    }

    private fun createEpisode(
        anime: SAnime,
        entry: LocalFileEntry,
        relativePath: String,
        displayName: String,
        imageLookup: Map<String, Uri>,
        episodesData: List<EpisodeDetails>?,
        episodeDataByIntNumber: Map<Int, EpisodeDetails>?,
    ): SEpisode {
        return SEpisode.create().apply {
            url = "${anime.url}/$relativePath"
            name = displayName
            date_upload = entry.lastModified

            val episodeNumber = EpisodeRecognition.parseEpisodeNumber(
                anime.title,
                this.name,
                this.episode_number.toDouble(),
            ).toFloat()
            episode_number = episodeNumber

            // Overwrite metadata from episodes.json using O(1) lookup
            val matchedData = episodeDataByIntNumber?.get((episodeNumber * EPISODE_KEY_SCALE).roundToInt())
                ?: episodesData?.firstOrNull { it.episode_number.equalsTo(episodeNumber) }
            matchedData?.also { data ->
                data.name?.also { name = it }
                data.date_upload?.also { date_upload = parseDate(it) }
                scanlator = data.scanlator
                summary = data.summary
                data.preview_url?.also { preview_url = it }
            }

            // Use local image thumbnail if present; otherwise point to lazy Coil video-frame URI
            if (this.preview_url == null) {
                val localThumb = thumbnailManager.findUriInLookup(imageLookup, this.name)
                    ?: thumbnailManager.findUriInLookup(imageLookup, entry.nameWithoutExtension)
                if (localThumb != null) {
                    this.preview_url = localThumb.toString()
                } else if (Archive.isDirectVideoExtension(entry.extension)) {
                    this.preview_url = buildVideoThumbnailUri(anime.url, relativePath, entry.lastModified)
                }
            }
        }
    }

    private fun parseDate(isoDate: String): Long {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(isoDate)?.time
        }.getOrNull() ?: 0L
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < FLOAT_EPSILON
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Unused")

    private fun scheduleCoverGeneration(animeUrl: String, animeTitle: String, videoEntry: LocalFileEntry) {
        val existingJob = coverGenJobs[animeUrl]
        if (existingJob?.isActive == true) return

        val job = scope.launch {
            try {
                coverGenSemaphore.withPermit {
                    if (coverManager.find(animeUrl) != null) return@withPermit
                    val targetAnime = SAnime.create().apply {
                        url = animeUrl
                        title = animeTitle
                    }
                    updateCoverFromVideo(videoEntry, targetAnime)
                    val savedCoverUri = targetAnime.thumbnail_url
                    if (!savedCoverUri.isNullOrEmpty()) {
                        coverUriCache[animeUrl] = savedCoverUri
                        runCatching {
                            val dbAnime = animeRepository.getAnimeByUrlAndSourceId(animeUrl, ID)
                            if (dbAnime != null && dbAnime.thumbnailUrl != savedCoverUri) {
                                animeRepository.update(
                                    AnimeUpdate(
                                        id = dbAnime.id,
                                        thumbnailUrl = savedCoverUri,
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Couldn't extract cover from video for $animeUrl" }
            } finally {
                coverGenJobs.remove(animeUrl)
            }
        }
        coverGenJobs[animeUrl] = job
    }

    private fun updateCoverFromVideo(videoEntry: LocalFileEntry, anime: SAnime) {
        val jpegBytes = extractCoverWithRetriever(videoEntry.uri)
            ?: extractCoverWithFFmpeg(videoEntry.uri)
            ?: return
        coverManager.update(anime, ByteArrayInputStream(jpegBytes))
    }

    private fun extractCoverWithRetriever(videoUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val seekMs = if (durationMs > 0L) {
                minOf(durationMs / COVER_SEEK_FRACTION, COVER_MAX_SEEK_MS)
            } else {
                COVER_FALLBACK_SEEK_MS
            }
            val seekUs = seekMs * US_PER_MS

            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    seekUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    COVER_MAX_DIM,
                    COVER_MAX_DIM,
                )
            } else {
                retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            val sourceBitmap = bitmap ?: return null
            val maxSide = max(sourceBitmap.width, sourceBitmap.height)
            val outputBitmap = if (maxSide > COVER_MAX_DIM) {
                val scale = COVER_MAX_DIM.toFloat() / maxSide.toFloat()
                val w = (sourceBitmap.width * scale).roundToInt().coerceAtLeast(1)
                val h = (sourceBitmap.height * scale).roundToInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(sourceBitmap, w, h, true)
                scaled
            } else {
                sourceBitmap
            }

            val baos = ByteArrayOutputStream()
            if (outputBitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, baos) && baos.size() > 0) {
                baos.toByteArray()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            scaled?.recycle()
            if (bitmap !== scaled) {
                bitmap?.recycle()
            }
            runCatching { retriever.release() }
        }
    }

    private fun extractCoverWithFFmpeg(videoUri: Uri): ByteArray? {
        val readParam = videoUri.toFFmpegReadString(context) ?: return null
        val tempFile = File.createTempFile("hikaru_cover_", ".jpg", context.cacheDir)
        return try {
            val outPath = tempFile.absolutePath.replace("\"", "\\\"")
            val primaryCmd = "-noaccurate_seek -ss $FFMPEG_COVER_SEEK_SEC -i \"$readParam\" " +
                "-frames:v 1 -vf scale=$COVER_MAX_DIM:-2 -q:v $FFMPEG_JPEG_QUALITY -y \"$outPath\""
            val session = FFmpegKit.execute(primaryCmd)
            if (!ReturnCode.isSuccess(session.returnCode) || tempFile.length() == 0L) {
                val fallbackCmd = "-noaccurate_seek -ss 1 -i \"$readParam\" " +
                    "-frames:v 1 -vf scale=$COVER_MAX_DIM:-2 -q:v $FFMPEG_JPEG_QUALITY -y \"$outPath\""
                FFmpegKit.execute(fallbackCmd)
            }
            if (tempFile.length() > 0L) tempFile.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"
        const val LOCAL_VIDEO_THUMB_SCHEME = "localvideo"

        private const val PAGE_SIZE = 30
        private const val COVER_FETCH_CONCURRENCY = 3
        private const val COVER_CACHE_PRUNE_FACTOR = 2
        private const val EPISODE_KEY_SCALE = 1000f
        private const val FLOAT_EPSILON = 0.0001f
        private const val COVER_MAX_DIM = 540
        private const val COVER_JPEG_QUALITY = 85
        private const val COVER_SEEK_FRACTION = 4L
        private const val COVER_MAX_SEEK_MS = 15_000L
        private const val COVER_FALLBACK_SEEK_MS = 3_000L
        private const val US_PER_MS = 1_000L
        private const val FFMPEG_COVER_SEEK_SEC = 15
        private const val FFMPEG_JPEG_QUALITY = 4
        private const val LATEST_THRESHOLD_DAYS = 7L
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(LATEST_THRESHOLD_DAYS, TimeUnit.DAYS)

        fun buildVideoThumbnailUri(
            animeUrl: String,
            episodeRelativePath: String,
            lastModified: Long,
        ): String {
            return Uri.Builder()
                .scheme(LOCAL_VIDEO_THUMB_SCHEME)
                .authority("frame")
                .appendQueryParameter("anime", animeUrl)
                .appendQueryParameter("ep", episodeRelativePath)
                .appendQueryParameter("lm", lastModified.toString())
                .build()
                .toString()
        }
    }
}

fun Anime.isLocal(): Boolean = source == LocalAnimeSource.ID
fun Source.isLocal(): Boolean = id == LocalAnimeSource.ID
fun DomainSource.isLocal(): Boolean = id == LocalAnimeSource.ID
