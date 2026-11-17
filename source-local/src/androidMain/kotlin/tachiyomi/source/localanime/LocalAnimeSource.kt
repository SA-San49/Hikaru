package tachiyomi.source.localanime

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import rx.Observable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.core.metadata.tachiyomi.EpisodeDetails
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.service.EpisodeRecognition
import tachiyomi.i18n.MR
import tachiyomi.source.localanime.filter.OrderBy
import tachiyomi.source.localanime.image.LocalAnimeSourceCoverManager
import tachiyomi.source.localanime.image.LocalAnimeSourceEpisodeThumbnailManager
import tachiyomi.source.localanime.io.Archive
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import tachiyomi.domain.source.model.Source as DomainSource

actual class LocalAnimeSource(
    private val context: Context,
    private val fileSystem: LocalAnimeSourceFileSystem,
    private val coverManager: LocalAnimeSourceCoverManager,
    private val thumbnailManager: LocalAnimeSourceEpisodeThumbnailManager,
) : CatalogueSource, UnmeteredSource {

    private data class CachedAnimeEntry(
        val dir: UniFile,
        val name: String,
        val lastModified: Long,
    )

    private val json: Json by injectLazy()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dirCacheMutex = Mutex()
    private val coverFetchSemaphore = Semaphore(4)

    @Volatile
    private var cachedDirEntries: List<CachedAnimeEntry> = emptyList()

    @Volatile
    private var cachedDirTimestamp: Long = 0L

    private val coverUriCache = ConcurrentHashMap<String, String>()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name = context.stringResource(MR.strings.local_anime_source)

    override val id: Long = ID

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

    private suspend fun getCachedAnimeEntries(): List<CachedAnimeEntry> {
        val now = System.currentTimeMillis()
        if (cachedDirEntries.isNotEmpty() && (now - cachedDirTimestamp) < DIR_CACHE_TTL_MS) {
            return cachedDirEntries
        }
        return dirCacheMutex.withLock {
            val currentNow = System.currentTimeMillis()
            if (cachedDirEntries.isNotEmpty() && (currentNow - cachedDirTimestamp) < DIR_CACHE_TTL_MS) {
                return@withLock cachedDirEntries
            }
            val entries = fileSystem.getFilesInBaseDirectory()
                .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
                .distinctBy { it.name }
                .map { dir ->
                    CachedAnimeEntry(
                        dir = dir,
                        name = dir.name.orEmpty(),
                        lastModified = dir.lastModified(),
                    )
                }
            coverUriCache.clear()
            cachedDirEntries = entries
            cachedDirTimestamp = System.currentTimeMillis()
            entries
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

        val allEntries = getCachedAnimeEntries()
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
                            val foundUri = coverManager.findInDirectory(entry.dir)?.uri?.toString().orEmpty()
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
        coverManager.find(anime.url)?.let {
            anime.thumbnail_url = it.uri.toString()
        }

        val animeDirFiles = fileSystem.getFilesInAnimeDirectory(anime.url)

        animeDirFiles
            .firstOrNull { it.extension == "json" && it.nameWithoutExtension == "details" }
            ?.let { file ->
                json.decodeFromStream<AnimeDetails>(file.openInputStream()).run {
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
        val episodesData = fileSystem.getFilesInAnimeDirectory(anime.url)
            .firstOrNull {
                it.extension == "json" && it.nameWithoutExtension == "episodes"
            }?.let { file ->
                runCatching {
                    json.decodeFromStream<List<EpisodeDetails>>(file.openInputStream())
                }.getOrNull()
            }

        val episodes = fileSystem.getFilesInAnimeDirectory(anime.url)
            // Only keep supported formats
            .filter { Archive.isSupported(it) }
            .map { episodeFile ->
                SEpisode.create().apply {
                    url = "${anime.url}/${episodeFile.name}"
                    name = episodeFile.nameWithoutExtension.orEmpty()
                    date_upload = episodeFile.lastModified()

                    val episodeNumber = EpisodeRecognition.parseEpisodeNumber(
                        anime.title,
                        this.name,
                        this.episode_number.toDouble(),
                    ).toFloat()
                    episode_number = episodeNumber

                    // Overwrite data from episodes.json file
                    episodesData?.also { dataList ->
                        dataList.firstOrNull { it.episode_number.equalsTo(episodeNumber) }
                            ?.also { data ->
                                data.name?.also { name = it }
                                data.date_upload?.also { date_upload = parseDate(it) }
                                scanlator = data.scanlator
                                summary = data.summary
                                data.preview_url?.also { preview_url = it }
                            }
                    }

                    // Look for local episode thumbnail file or extract frame from video if thumbnail is missing
                    if (this.preview_url == null) {
                        val thumbnailFile = thumbnailManager.find(anime.url, this.name)
                        if (thumbnailFile != null) {
                            this.preview_url = thumbnailFile.uri.toString()
                        } else {
                            try {
                                updateEpisodeThumbnailFromVideo(this, anime)
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR) { "Couldn't extract episode thumbnail from video: $e" }
                            }
                        }
                    }
                }
            }
            .sortedWith { e1, e2 ->
                val e = e2.episode_number.compareTo(e1.episode_number)
                if (e == 0) e2.name.compareToCaseInsensitiveNaturalOrder(e1.name) else e
            }

        // Generate the cover from the first episode found if not available
        if (anime.thumbnail_url == null) {
            scope.launch {
                try {
                    episodes.lastOrNull()?.let { episode ->
                        updateCoverFromVideo(episode, anime)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Couldn't extract thumbnail from video: $e" }
                }
            }
        }

        episodes
    }

    private fun parseDate(isoDate: String): Long {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(isoDate)?.time ?: 0L
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Unused")

    private fun updateCoverFromVideo(episode: SEpisode, anime: SAnime) {
        val tempFile = File.createTempFile(
            "tmp_",
            anime.title + DEFAULT_COVER_NAME,
        )
        val outFile = tempFile.path

        val episodeName = episode.url.split('/', limit = 2).last()
        val animeDir = fileSystem.getAnimeDirectory(anime.url)!!
        val episodeFile = animeDir.findFile(episodeName)!!
        val episodeFilename = { episodeFile.toFFmpegString(context) }

        val ffProbe = com.arthenica.ffmpegkit.FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"${episodeFilename()}\"",
        )
        val duration = ffProbe.allLogsAsString.trim().toFloat()
        val second = duration.toInt() / 2

        com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-ss $second -i \"${episodeFilename()}\" -frames:v 1 -update true \"$outFile\" -y",
        )

        if (tempFile.length() > 0L) {
            coverManager.update(anime, tempFile.inputStream())
        }
    }

    private fun updateEpisodeThumbnailFromVideo(episode: SEpisode, anime: SAnime) {
        val tempFile = File.createTempFile(
            "tmp_",
            anime.title + episode.name + DEFAULT_THUMBNAIL_NAME,
        )
        val outFile = tempFile.path

        val episodeName = episode.url.split('/', limit = 2).last()
        val animeDir = fileSystem.getAnimeDirectory(anime.url)!!
        val episodeFile = animeDir.findFile(episodeName)!!
        val episodeFilename = { episodeFile.toFFmpegString(context) }

        val ffProbe = com.arthenica.ffmpegkit.FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"${episodeFilename()}\"",
        )
        val duration = ffProbe.allLogsAsString.trim().toFloat()
        val second = duration.toInt() / 2

        com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-ss $second -i \"${episodeFilename()}\" -frames:v 1 -update true \"$outFile\" -y",
        )

        if (tempFile.length() > 0L) {
            thumbnailManager.update(anime, episode, tempFile.inputStream())
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"

        private const val PAGE_SIZE = 30
        private const val DIR_CACHE_TTL_MS = 15_000L

        private const val DEFAULT_COVER_NAME = "cover.jpg"
        private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
    }
}

fun Anime.isLocal(): Boolean = source == LocalAnimeSource.ID

fun Source.isLocal(): Boolean = id == LocalAnimeSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalAnimeSource.ID
