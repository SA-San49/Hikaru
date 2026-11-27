package eu.kanade.tachiyomi.data.telegram

import android.app.Application
import android.content.Context
import android.net.Uri
import eu.kanade.domain.telegram.TelegramPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("MagicNumber", "TooManyFunctions")
class TelegramCatalogueSource(
    val entry: TelegramBrowseSources.Entry,
) : AnimeHttpSource(), ConfigurableAnimeSource {

    override val id: Long = entry.sourceId
    override val name: String = entry.title
    override val baseUrl: String = "https://t.me"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes: Boolean = false

    private data class CachedPoster(
        val fileId: Int,
        val chatId: Long,
        val messageId: Long,
        val fallbackThumb: String?,
        val upgradedTitle: String,
        val upgradedDisplayTitle: String,
    )

    private val appContext: Context by lazy { Injekt.get<Application>().applicationContext }
    private val pageCursors = ConcurrentHashMap<Int, Long>()
    private val searchCursors = ConcurrentHashMap<String, Long>()
    private val collectedVideos = ConcurrentHashMap<Long, Td.ChatVideo>()
    private val collectedImages = ConcurrentHashMap<Long, Td.ChatImage>()
    private val seriesCache = ConcurrentHashMap<String, TelegramAnimeParser.GroupedSeries>()
    private val posterCache = ConcurrentHashMap<String, CachedPoster>()
    private val posterMissKeys = ConcurrentHashMap.newKeySet<String>()
    private val seenSeriesKeys = ConcurrentHashMap.newKeySet<String>()
    private val telegramPrefs: TelegramPreferences by lazy { Injekt.get() }
    private val mergedUrlOverrides = ConcurrentHashMap<String, String>()
    private val keepCacheOnNextRefresh = AtomicBoolean(false)

    @Volatile
    private var lastMoreAvailable: Boolean = false

    @Volatile
    private var lastBatchCursor: Long = 0L

    fun markKeepCacheOnRefresh() {
        keepCacheOnNextRefresh.set(true)
    }

    fun cachedAliasesFor(key: String): Set<String> =
        seriesCache[key]?.aliasKeys ?: emptySet()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_QUALITY
            title = "Preferred quality"
            entries = arrayOf(
                "HLS (Match display)",
                "Original (Uploaded quality)",
                "1080p",
                "720p",
                "480p",
                "360p",
            )
            entryValues = arrayOf("hls", "original", "1080", "720", "480", "360")
            setDefaultValue(DEFAULT_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)
    }

    fun preferredQuality(): String =
        getSourcePreferences()
            .getString(PREF_PREFERRED_QUALITY, DEFAULT_QUALITY)
            .orEmpty()
            .ifBlank { DEFAULT_QUALITY }

    private fun isValidEpisodeVideo(v: Td.ChatVideo): Boolean {
        if (Td.isImageFilePath(v.fileName)) return false
        if (!v.isAnimation) return true
        return v.duration > MIN_ANIMATION_SECS || v.size >= MIN_ANIMATION_BYTES
    }

    private suspend fun resolveChatId(): Long {
        if (entry.chatId != 0L) {
            if (Td.ensureReady(appContext)) {
                Td.ensureChatLoaded(entry.chatId)
            }
            return entry.chatId
        }
        if (entry.handle.isNotBlank() && Td.ensureReady(appContext)) {
            val found = Td.joinedChat(entry.handle)?.first ?: Td.publicChatId(entry.handle)
            if (found != null && found != 0L) {
                Td.ensureChatLoaded(found)
                return found
            }
        }
        return 0L
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = loadChannelPage(page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = loadChannelPage(page)

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private suspend fun loadChannelPage(page: Int): AnimesPage {
        val reuseCachedPage1 = page <= 1 &&
            keepCacheOnNextRefresh.compareAndSet(true, false) &&
            collectedVideos.isNotEmpty()
        if (reuseCachedPage1) {
            val chatId = resolveChatId()
            if (chatId != 0L) {
                pageCursors.clear()
                seenSeriesKeys.clear()
                seriesCache.clear()
                val grouped = applyUserMerges(
                    TelegramAnimeParser.groupVideosAndPosters(
                        videos = collectedVideos.values.toList(),
                        images = collectedImages.values.toList(),
                    ),
                )
                val cachedList = grouped.map { g ->
                    val cached = posterCache[g.groupKey] ?: g.aliasKeys.firstNotNullOfOrNull { posterCache[it] }
                    val finalGroup = if (!g.hasRealPoster && cached != null) applyCachedPoster(g, cached) else g
                    seriesCache[finalGroup.groupKey] = finalGroup
                    finalGroup.aliasKeys.forEach { ak -> seriesCache[ak] = finalGroup }
                    seenSeriesKeys.add(finalGroup.groupKey)
                    seenSeriesKeys.addAll(finalGroup.aliasKeys)
                    finalGroup
                }
                if (lastBatchCursor != 0L) {
                    pageCursors[2] = lastBatchCursor
                }
                val items = cachedList.map { seriesToSAnime(chatId, it) }
                return AnimesPage(items, lastMoreAvailable && lastBatchCursor != 0L)
            }
        }
        if (page <= 1) {
            keepCacheOnNextRefresh.set(false)
            pageCursors.clear()
            seenSeriesKeys.clear()
            collectedVideos.clear()
            collectedImages.clear()
            seriesCache.clear()
            posterMissKeys.clear()
            mergedUrlOverrides.clear()
            lastMoreAvailable = false
            lastBatchCursor = 0L
        }
        var before = if (page <= 1) 0L else (pageCursors[page] ?: 0L)
        val chatId = resolveChatId()
        if (chatId != 0L && Td.isSignedIn()) {
            val newGroups = ArrayList<TelegramAnimeParser.GroupedSeries>()
            var moreAvailable = false
            var rounds = 0
            while (rounds < MAX_SCAN_ROUNDS) {
                rounds++
                val batch = Td.chatCatalogueBatch(
                    chatId = chatId,
                    before = before,
                    targetMessages = CATALOGUE_SCAN_MESSAGES,
                )
                batch.videos.asSequence()
                    .filter(::isValidEpisodeVideo)
                    .forEach { collectedVideos[it.messageId] = it }
                batch.images.forEach { collectedImages[it.messageId] = it }

                val grouped = applyUserMerges(
                    TelegramAnimeParser.groupVideosAndPosters(
                        videos = collectedVideos.values.toList(),
                        images = collectedImages.values.toList(),
                    ),
                )
                grouped.forEach { group ->
                    seriesCache[group.groupKey] = group
                    group.aliasKeys.forEach { ak -> seriesCache[ak] = group }
                    val alreadySeen = group.groupKey in seenSeriesKeys ||
                        group.aliasKeys.any { it in seenSeriesKeys }
                    seenSeriesKeys.add(group.groupKey)
                    seenSeriesKeys.addAll(group.aliasKeys)
                    if (!alreadySeen) {
                        newGroups += group
                    }
                }
                before = batch.nextCursor
                moreAvailable = batch.more && batch.nextCursor != 0L
                if (newGroups.isNotEmpty() || !moreAvailable) break
            }
            val resolvedGroups = resolveMissingPosters(chatId, newGroups)
            val newItems = resolvedGroups.map { seriesToSAnime(chatId, it) }
            if (before != 0L) {
                pageCursors[page + 1] = before
            }
            lastBatchCursor = before
            lastMoreAvailable = moreAvailable
            return AnimesPage(newItems, moreAvailable && before != 0L)
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

    @Suppress("ReturnCount")
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
            val validVideos = res.videos.filter(::isValidEpisodeVideo)
            validVideos.forEach { collectedVideos[it.messageId] = it }
            val images = if (collectedImages.isNotEmpty()) {
                collectedImages.values.toList()
            } else {
                Td.chatRecentImages(chatId).also { list ->
                    list.forEach { collectedImages[it.messageId] = it }
                }
            }
            val grouped = applyUserMerges(TelegramAnimeParser.groupVideosAndPosters(validVideos, images))
            val resolved = resolveMissingPosters(chatId, grouped)
            val items = resolved.map { seriesToSAnime(chatId, it) }
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
        if (anime.url.startsWith("/tg/series")) {
            val uri = Uri.parse("https://t.me" + anime.url)
            val chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: resolveChatId()
            val groupKey = uri.getQueryParameter("key").orEmpty()
            val seriesTitle = uri.getQueryParameter("title").orEmpty().ifBlank { anime.title }
            val targetSeason = uri.getQueryParameter("season")?.toIntOrNull() ?: 1
            val targetRawSeason = uri.getQueryParameter("rawSeason")?.toIntOrNull() ?: 0
            val targetIsFinal = uri.getQueryParameter("final")?.toBooleanStrictOrNull() ?: false
            val targetKind = uri.getQueryParameter("kind")?.let { k ->
                TelegramAnimeParser.SeriesKind.entries.firstOrNull { it.name == k }
            } ?: TelegramAnimeParser.SeriesKind.MAIN

            val cachedSeries = seriesCache[groupKey]
            val cachedPoster = posterCache[groupKey]
            val needsRealPoster = cachedPoster == null && cachedSeries?.hasRealPoster != true
            if (needsRealPoster && chatId != 0L && Td.isSignedIn() && groupKey !in posterMissKeys) {
                val chunks = TelegramAnimeParser.buildPosterSearchChunks(
                    seriesTitle = seriesTitle,
                    rawSeason = targetRawSeason,
                    kind = targetKind,
                    isFinalSeason = targetIsFinal,
                )
                if (chunks.isNotEmpty()) {
                    var completed = false
                    val matched = withTimeoutOrNull(POSTER_SEARCH_TIMEOUT_MS) {
                        Td.searchPosterByChunks(chatId, chunks, collectedImages) { img ->
                            TelegramAnimeParser.matchesSeriesPoster(
                                targetSeriesTitle = seriesTitle,
                                targetGroupKey = groupKey,
                                targetKind = targetKind,
                                targetSeason = targetSeason,
                                image = img,
                            ) != null
                        }.also { completed = true }
                    }
                    if (matched != null && matched.fileId != 0) {
                        val imgParsed = TelegramAnimeParser.parseImage(matched)
                        val bestTitle = TelegramAnimeParser.chooseBestSeriesTitle(seriesTitle, imgParsed?.seriesTitle)
                        val bestDisplay = TelegramAnimeParser.formatSeriesDisplayTitle(bestTitle, targetSeason, targetKind)
                        val entryPoster = CachedPoster(
                            fileId = matched.fileId,
                            chatId = matched.chatId,
                            messageId = matched.messageId,
                            fallbackThumb = matched.minithumb,
                            upgradedTitle = bestTitle,
                            upgradedDisplayTitle = bestDisplay,
                        )
                        posterCache[groupKey] = entryPoster
                        cachedSeries?.let { s ->
                            val upgraded = applyCachedPoster(s, entryPoster)
                            seriesCache[groupKey] = upgraded
                            upgraded.aliasKeys.forEach { ak ->
                                posterCache[ak] = entryPoster
                                seriesCache[ak] = upgraded
                            }
                        }
                        anime.thumbnail_url = TdHttpServer.thumbUrlFor(matched.fileId, matched.chatId, matched.messageId)
                    } else if (completed) {
                        posterMissKeys += groupKey
                    }
                }
            } else if (cachedPoster != null && cachedPoster.fileId != 0) {
                anime.thumbnail_url = TdHttpServer.thumbUrlFor(
                    cachedPoster.fileId,
                    cachedPoster.chatId,
                    cachedPoster.messageId,
                )
            }
        }
        return anime.apply { initialized = true }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
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

        if (anime.url.startsWith("/tg/series")) {
            val chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: resolveChatId()
            val groupKey = uri.getQueryParameter("key").orEmpty()
            val seriesTitle = uri.getQueryParameter("title").orEmpty().ifBlank { anime.title }
            val targetSeason = uri.getQueryParameter("season")?.toIntOrNull() ?: 1
            val targetKind = uri.getQueryParameter("kind")?.let { k ->
                TelegramAnimeParser.SeriesKind.entries.firstOrNull { it.name == k }
            } ?: TelegramAnimeParser.SeriesKind.MAIN
            val mergeRule = TelegramSeriesMergeStore.ruleForKey(telegramPrefs, id, groupKey)
            val allMatchKeys = linkedSetOf(groupKey).apply {
                if (mergeRule != null) {
                    add(mergeRule.targetKey)
                    addAll(mergeRule.memberKeys)
                }
            }
            val merged = LinkedHashMap<Long, TelegramAnimeParser.ParsedVideo>()

            var cachedSeries = seriesCache[groupKey]
            for (k in allMatchKeys) {
                seriesCache[k]?.videos?.forEach { pv ->
                    if (isValidEpisodeVideo(pv.video)) {
                        merged[pv.video.messageId] = pv
                    }
                }
            }
            val coldStart = merged.isEmpty()

            if (chatId != 0L && Td.ensureReady(appContext)) {
                Td.ensureChatLoaded(chatId)
                if (coldStart) {
                    val batch = Td.chatCatalogueBatch(
                        chatId = chatId,
                        before = 0L,
                        targetMessages = CATALOGUE_SCAN_MESSAGES,
                    )
                    batch.videos.asSequence()
                        .filter(::isValidEpisodeVideo)
                        .forEach { collectedVideos[it.messageId] = it }
                    batch.images.forEach { collectedImages[it.messageId] = it }
                    val grouped = applyUserMerges(
                        TelegramAnimeParser.groupVideosAndPosters(
                            videos = collectedVideos.values.toList(),
                            images = collectedImages.values.toList(),
                        ),
                    )
                    grouped.forEach { g ->
                        seriesCache[g.groupKey] = g
                        g.aliasKeys.forEach { ak -> seriesCache[ak] = g }
                    }
                    cachedSeries = seriesCache[groupKey]
                        ?: grouped.firstOrNull { groupKey in it.aliasKeys }
                    for (k in allMatchKeys) {
                        seriesCache[k]?.videos?.forEach { pv ->
                            if (isValidEpisodeVideo(pv.video)) {
                                merged[pv.video.messageId] = pv
                            }
                        }
                    }
                }

                val aliases = linkedSetOf<String>().apply {
                    addAll(cachedSeries?.aliasKeys ?: emptySet())
                    addAll(allMatchKeys)
                }
                val memberMatchTitles = mergeRule?.memberTitles.orEmpty()
                val primaryQuery = TelegramAnimeParser.buildSearchQuery(seriesTitle)
                val chunkQueries = TelegramAnimeParser.buildPosterSearchChunks(seriesTitle)
                val memberQueries = memberMatchTitles.mapNotNull { title ->
                    TelegramAnimeParser.buildSearchQuery(title).takeIf { it.isNotBlank() }
                }
                val allQueries = (
                    listOfNotNull(primaryQuery.takeIf { it.isNotBlank() }) +
                        chunkQueries +
                        memberQueries
                    ).distinct().take(MAX_EPISODE_SEARCH_CHUNKS)

                for ((idx, qText) in allQueries.withIndex()) {
                    if (idx > 0 && mergeRule == null) {
                        val minSoFar = merged.values.mapNotNull { it.parsed.episodeNumber }.minOrNull()
                        val seemsComplete = merged.size > 1 && (minSoFar == null || minSoFar <= 1f)
                        if (seemsComplete) break
                    }
                    val fastHits = Td.searchChatVideos(
                        chatId = chatId,
                        text = qText,
                        inText = Td.SearchIn.CHAT,
                        cursor = 0L,
                        limit = EPISODE_SEARCH_LIMIT,
                    )
                    for (v in fastHits.videos) {
                        if (!isValidEpisodeVideo(v) || merged.containsKey(v.messageId)) continue
                        matchAnyMergedSeries(
                            video = v,
                            groupKey = groupKey,
                            seriesTitle = seriesTitle,
                            targetKind = targetKind,
                            targetSeason = targetSeason,
                            aliases = aliases,
                            extraKeys = allMatchKeys,
                            extraTitles = memberMatchTitles,
                        )?.let { merged[v.messageId] = it }
                    }
                }

                val minEp = merged.values.mapNotNull { it.parsed.episodeNumber }.minOrNull()
                val tailCursor = pageCursors[pageCursors.keys.maxOrNull() ?: 2] ?: 0L
                val missingEarly = minEp != null && minEp > 1f && tailCursor != 0L
                if (missingEarly) {
                    val extraBatch = Td.chatCatalogueBatch(
                        chatId = chatId,
                        before = tailCursor,
                        targetMessages = BACKFILL_SCAN_MESSAGES,
                    )
                    extraBatch.videos.asSequence()
                        .filter(::isValidEpisodeVideo)
                        .forEach { collectedVideos[it.messageId] = it }
                    extraBatch.images.forEach { collectedImages[it.messageId] = it }
                    for (v in extraBatch.videos) {
                        if (!isValidEpisodeVideo(v) || merged.containsKey(v.messageId)) continue
                        matchAnyMergedSeries(
                            video = v,
                            groupKey = groupKey,
                            seriesTitle = seriesTitle,
                            targetKind = targetKind,
                            targetSeason = targetSeason,
                            aliases = aliases,
                            extraKeys = allMatchKeys,
                            extraTitles = memberMatchTitles,
                        )?.let { merged[v.messageId] = it }
                    }
                }

                if (coldStart && merged.isEmpty() && primaryQuery.isNotBlank()) {
                    val fallbackHits = Td.searchChatVideos(
                        chatId = chatId,
                        text = primaryQuery,
                        inText = Td.SearchIn.VIDEO,
                        cursor = 0L,
                        limit = EPISODE_SEARCH_LIMIT,
                    )
                    for (v in fallbackHits.videos) {
                        if (!isValidEpisodeVideo(v) || merged.containsKey(v.messageId)) continue
                        matchAnyMergedSeries(
                            video = v,
                            groupKey = groupKey,
                            seriesTitle = seriesTitle,
                            targetKind = targetKind,
                            targetSeason = targetSeason,
                            aliases = aliases,
                            extraKeys = allMatchKeys,
                            extraTitles = memberMatchTitles,
                        )?.let { merged[v.messageId] = it }
                    }
                }
            }

            val parsedList = merged.values.sortedWith(TelegramAnimeParser.EPISODE_SORT_COMPARATOR)

            return parsedList.mapIndexed { idx, pv ->
                val v = pv.video
                val parsed = pv.parsed
                val epNum = parsed.episodeNumber ?: (idx + 1).toFloat()
                val resTag = when {
                    v.width > 0 || v.height > 0 -> Td.resolutionTag(v.width, v.height)
                    !parsed.qualityTag.isNullOrBlank() -> parsed.qualityTag
                    else -> null
                }
                val metaParts = buildList {
                    resTag?.let(::add)
                    if (v.size > 0L) add(formatBytes(v.size))
                }
                SEpisode.create().apply {
                    url = buildTdItemUrl(v, parsed)
                    name = parsed.episodeLabel
                    episode_number = epNum
                    date_upload = v.date.toLong() * 1000L
                    scanlator = metaParts.joinToString(" • ").ifBlank { entry.title }
                }
            }.reversed()
        }

        val chatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: resolveChatId()
        val msgId = uri.getQueryParameter("msg")?.toLongOrNull() ?: 0L
        val postVideos = if (chatId != 0L && msgId != 0L && Td.ensureReady(appContext)) {
            Td.ensureChatLoaded(chatId)
            Td.postVideos(chatId, msgId).filter(::isValidEpisodeVideo)
        } else {
            emptyList()
        }
        if (postVideos.size > 1) {
            return postVideos.mapIndexed { idx, v ->
                val parsed = TelegramAnimeParser.parseVideo(v)
                val epNum = parsed.episodeNumber ?: (idx + 1).toFloat()
                SEpisode.create().apply {
                    url = buildTdItemUrl(v)
                    name = parsed.episodeLabel
                    episode_number = epNum
                    date_upload = v.date.toLong() * 1000L
                    scanlator = listOfNotNull(
                        Td.resolutionTag(v.width, v.height).takeIf { v.width > 0 || v.height > 0 } ?: parsed.qualityTag,
                        formatBytes(v.size).takeIf { v.size > 0L },
                    ).joinToString(" • ")
                }
            }.reversed()
        }

        val single = postVideos.firstOrNull()
        val width = single?.width ?: (uri.getQueryParameter("w")?.toIntOrNull() ?: 0)
        val height = single?.height ?: (uri.getQueryParameter("h")?.toIntOrNull() ?: 0)
        val size = single?.size ?: (uri.getQueryParameter("size")?.toLongOrNull() ?: 0L)
        val dateSec = single?.date?.toLong() ?: (uri.getQueryParameter("date")?.toLongOrNull() ?: 0L)
        val epTitle = single?.let { TelegramAnimeParser.parseVideo(it).episodeLabel }
            ?: uri.getQueryParameter("title").orEmpty().ifBlank { anime.title }
        return listOf(
            SEpisode.create().apply {
                url = single?.let { buildTdItemUrl(it) } ?: anime.url
                name = epTitle
                episode_number = 1f
                date_upload = dateSec * 1000L
                scanlator = listOfNotNull(
                    Td.resolutionTag(width, height).takeIf { width > 0 || height > 0 },
                    formatBytes(size).takeIf { size > 0L },
                ).joinToString(" • ")
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

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val uri = Uri.parse("https://t.me" + episode.url)
        val mode = uri.getQueryParameter("mode").orEmpty()
        val prefQ = preferredQuality()
        val displayShort = Td.displayShortSide(appContext)
        if (mode == "web") {
            val videoUrl = uri.getQueryParameter("v").orEmpty()
            val referer = uri.getQueryParameter("ref").orEmpty()
            val reqHeaders = Headers.Builder().apply {
                add("User-Agent", network.defaultUserAgentProvider())
                if (referer.isNotBlank()) add("Referer", referer)
            }.build()
            return Td.buildQualityVideos(
                baseUrl = videoUrl,
                width = 0,
                height = 0,
                headers = reqHeaders,
                preferredQuality = prefQ,
                displayShortSide = displayShort,
            )
        }
        val uriChatId = uri.getQueryParameter("chat")?.toLongOrNull() ?: 0L
        val msgId = uri.getQueryParameter("msg")?.toLongOrNull() ?: 0L
        var fileId = uri.getQueryParameter("file")?.toIntOrNull() ?: 0
        val width = uri.getQueryParameter("w")?.toIntOrNull() ?: 0
        val height = uri.getQueryParameter("h")?.toIntOrNull() ?: 0

        val fastState = if (fileId != 0) Td.fileState(fileId) else null
        val verifiedFast = fileId != 0 &&
            Td.downloadError(fileId) == null &&
            Td.isVerifiedVideoFile(fileId)
        val stateReady = fastState?.canBeDownloaded == true && !Td.isImageFilePath(fastState.path)
        val fastReady = uriChatId != 0L && Td.isSignedIn() && verifiedFast && stateReady

        val chatId = if (fastReady) {
            uriChatId
        } else {
            val resolved = if (uriChatId != 0L) uriChatId else resolveChatId()
            Td.ensureReady(appContext)
            if (resolved != 0L) Td.ensureChatLoaded(resolved)
            resolved
        }

        if (!fastReady) {
            val state = if (fileId != 0) Td.stateOf(fileId) else null
            val invalidState = state?.canBeDownloaded != true || Td.isImageFilePath(state.path)
            val invalidFile = fileId == 0 || Td.downloadError(fileId) != null || !Td.isVerifiedVideoFile(fileId)
            val needsRefresh = invalidState || invalidFile
            if (needsRefresh && chatId != 0L && msgId != 0L) {
                if (fileId != 0) Td.clearDownloadError(fileId)
                val refreshed = Td.touchMessage(chatId, msgId)
                if (refreshed != 0 && Td.isVerifiedVideoFile(refreshed)) {
                    fileId = refreshed
                } else {
                    return emptyList()
                }
            }
            if (fileId == 0 || !Td.isVerifiedVideoFile(fileId)) {
                return emptyList()
            }
        }

        val fileSize = fastState?.size ?: Td.fileState(fileId)?.size
            ?: uri.getQueryParameter("size")?.toLongOrNull()
            ?: 0L
        Td.prewarm(fileId, fileSize)
        val streamUrl = TdHttpServer.urlFor(
            fileId = fileId,
            chatId = chatId,
            messageId = msgId,
            isExtension = true,
        )
        return Td.buildQualityVideos(
            baseUrl = streamUrl,
            width = width,
            height = height,
            preferredQuality = prefQ,
            displayShortSide = displayShort,
        )
    }

    private suspend fun resolveMissingPosters(
        chatId: Long,
        groups: List<TelegramAnimeParser.GroupedSeries>,
    ): List<TelegramAnimeParser.GroupedSeries> {
        if (groups.isEmpty()) return groups

        val preResolved = groups.map { g ->
            if (g.hasRealPoster) {
                val cached = CachedPoster(
                    fileId = g.posterFileId,
                    chatId = g.posterChatId,
                    messageId = g.posterMessageId,
                    fallbackThumb = g.posterFallbackThumb,
                    upgradedTitle = g.seriesTitle,
                    upgradedDisplayTitle = g.displayTitle,
                )
                posterCache[g.groupKey] = cached
                g.aliasKeys.forEach { ak -> posterCache[ak] = cached }
                g
            } else {
                val cached = posterCache[g.groupKey] ?: g.aliasKeys.firstNotNullOfOrNull { posterCache[it] }
                if (cached != null) applyCachedPoster(g, cached) else g
            }
        }

        val toSearch = preResolved.filter { !it.hasRealPoster && it.groupKey !in posterMissKeys }
        if (toSearch.isEmpty()) return preResolved

        val batchDiscoveredImages = ConcurrentHashMap<Long, Td.ChatImage>()
        val sem = Semaphore(POSTER_SEARCH_CONCURRENCY)
        coroutineScope {
            toSearch.map { g ->
                async {
                    sem.withPermit {
                        if (posterCache.containsKey(g.groupKey)) return@withPermit
                        // Re-check only newly discovered images from sibling poster searches in this pass
                        val localHit = batchDiscoveredImages.values.firstOrNull { img ->
                            TelegramAnimeParser.matchesSeriesPoster(
                                targetSeriesTitle = g.seriesTitle,
                                targetGroupKey = g.groupKey,
                                targetKind = g.kind,
                                targetSeason = g.effectiveSeason,
                                image = img,
                            ) != null
                        }
                        var completed = localHit != null
                        val matched = localHit ?: run {
                            val chunks = TelegramAnimeParser.buildPosterSearchChunks(
                                seriesTitle = g.seriesTitle,
                                rawSeason = g.rawSeason,
                                kind = g.kind,
                                isFinalSeason = g.isFinalSeason,
                            )
                            if (chunks.isEmpty()) {
                                completed = true
                                null
                            } else {
                                withTimeoutOrNull(POSTER_SEARCH_TIMEOUT_MS) {
                                    Td.searchPosterByChunks(chatId, chunks, batchDiscoveredImages) { img ->
                                        collectedImages.putIfAbsent(img.messageId, img)
                                        TelegramAnimeParser.matchesSeriesPoster(
                                            targetSeriesTitle = g.seriesTitle,
                                            targetGroupKey = g.groupKey,
                                            targetKind = g.kind,
                                            targetSeason = g.effectiveSeason,
                                            image = img,
                                        ) != null
                                    }.also { hit ->
                                        if (hit != null) {
                                            collectedImages.putIfAbsent(hit.messageId, hit)
                                        }
                                        completed = true
                                    }
                                }
                            }
                        }
                        if (matched != null && matched.fileId != 0) {
                            val imgParsed = TelegramAnimeParser.parseImage(matched)
                            val bestTitle = TelegramAnimeParser.chooseBestSeriesTitle(g.seriesTitle, imgParsed?.seriesTitle)
                            val bestDisplay = TelegramAnimeParser.formatSeriesDisplayTitle(
                                bestTitle,
                                g.effectiveSeason,
                                g.kind,
                            )
                            val cached = CachedPoster(
                                fileId = matched.fileId,
                                chatId = matched.chatId,
                                messageId = matched.messageId,
                                fallbackThumb = matched.minithumb,
                                upgradedTitle = bestTitle,
                                upgradedDisplayTitle = bestDisplay,
                            )
                            posterCache[g.groupKey] = cached
                            g.aliasKeys.forEach { ak -> posterCache[ak] = cached }
                        } else if (completed) {
                            posterMissKeys += g.groupKey
                        }
                    }
                }
            }.awaitAll()
        }

        return preResolved.map { g ->
            val updated = if (!g.hasRealPoster) {
                val cached = posterCache[g.groupKey] ?: g.aliasKeys.firstNotNullOfOrNull { posterCache[it] }
                if (cached != null) applyCachedPoster(g, cached) else g
            } else {
                g
            }
            seriesCache[updated.groupKey] = updated
            updated.aliasKeys.forEach { ak -> seriesCache[ak] = updated }
            updated
        }
    }

    private fun applyCachedPoster(
        g: TelegramAnimeParser.GroupedSeries,
        cached: CachedPoster,
    ): TelegramAnimeParser.GroupedSeries = g.copy(
        posterFileId = cached.fileId,
        posterChatId = cached.chatId,
        posterMessageId = cached.messageId,
        posterFallbackThumb = cached.fallbackThumb ?: g.posterFallbackThumb,
        seriesTitle = cached.upgradedTitle.ifBlank { g.seriesTitle },
        displayTitle = cached.upgradedDisplayTitle.ifBlank { g.displayTitle },
        hasRealPoster = cached.fileId != 0,
    )

    private fun seriesToSAnime(chatId: Long, series: TelegramAnimeParser.GroupedSeries): SAnime {
        val firstVideo = series.videos.firstOrNull()?.video
        val detectedRes = series.videos.firstNotNullOfOrNull { pv ->
            when {
                pv.video.width > 0 || pv.video.height > 0 -> Td.resolutionTag(pv.video.width, pv.video.height)
                !pv.parsed.qualityTag.isNullOrBlank() -> pv.parsed.qualityTag
                else -> null
            }
        }
        val epCount = series.videos.size
        val kindTag = series.kind.label.takeIf { it.isNotBlank() }
        val seasonTag = if (series.effectiveSeason >= 2) "Season ${series.effectiveSeason}" else null
        val overriddenUrl = mergedUrlOverrides[series.groupKey]
        return SAnime.create().apply {
            url = overriddenUrl ?: (
                "/tg/series?chat=$chatId" +
                    "&key=${Uri.encode(series.groupKey)}" +
                    "&title=${Uri.encode(series.seriesTitle)}" +
                    "&disp=${Uri.encode(series.displayTitle)}" +
                    "&season=${series.effectiveSeason}" +
                    "&rawSeason=${series.rawSeason}" +
                    "&final=${series.isFinalSeason}" +
                    "&kind=${series.kind.name}"
                )
            title = series.displayTitle
            author = entry.title
            artist = listOfNotNull(kindTag, seasonTag, detectedRes).joinToString(" • ").ifBlank { null }
            genre = listOfNotNull("Telegram", kindTag, seasonTag, detectedRes).joinToString(", ")
            status = SAnime.COMPLETED
            thumbnail_url = when {
                series.posterFileId != 0 -> TdHttpServer.thumbUrlFor(
                    fileId = series.posterFileId,
                    chatId = series.posterChatId,
                    messageId = series.posterMessageId,
                )
                !series.posterFallbackThumb.isNullOrBlank() &&
                    series.posterFallbackThumb.startsWith("http", ignoreCase = true) -> series.posterFallbackThumb
                else -> null
            }
            description = buildString {
                appendLine(series.displayTitle)
                append("Episodes: $epCount")
                detectedRes?.let { append(" • $it") }
                firstVideo?.caption?.trim()?.takeIf { it.isNotBlank() }?.let { cap ->
                    appendLine()
                    appendLine()
                    append(cap)
                }
            }
            initialized = true
        }
    }

    private fun webVideoToSAnime(v: TelegramVideo): SAnime {
        val clean = v.title.lineSequence().firstOrNull { it.isNotBlank() }
            ?.replace('_', ' ')
            ?.trim()
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

    private fun buildTdItemUrl(
        v: Td.ChatVideo,
        preParsed: TelegramAnimeParser.ParsedMedia? = null,
    ): String {
        val parsed = preParsed ?: TelegramAnimeParser.parseVideo(v)
        return "/tg/item?mode=td" +
            "&chat=${v.chatId}" +
            "&msg=${v.messageId}" +
            "&file=${v.fileId}" +
            "&w=${v.width}" +
            "&h=${v.height}" +
            "&dur=${v.duration}" +
            "&size=${v.size}" +
            "&date=${v.date}" +
            "&title=${Uri.encode(parsed.episodeLabel)}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun applyUserMerges(
        groups: List<TelegramAnimeParser.GroupedSeries>,
    ): List<TelegramAnimeParser.GroupedSeries> {
        val rules = TelegramSeriesMergeStore.rulesForSource(telegramPrefs, id)
        if (rules.isEmpty() || groups.isEmpty()) return groups

        val keyToRule = HashMap<String, TelegramSeriesMergeStore.MergedSeriesRule>()
        val rulesByTarget = HashMap<String, TelegramSeriesMergeStore.MergedSeriesRule>()
        for (rule in rules) {
            rulesByTarget[rule.targetKey] = rule
            keyToRule[rule.targetKey] = rule
            for (mk in rule.memberKeys) {
                keyToRule[mk] = rule
            }
        }

        val out = ArrayList<TelegramAnimeParser.GroupedSeries>(groups.size)
        val buckets = LinkedHashMap<String, MutableList<TelegramAnimeParser.GroupedSeries>>()
        for (g in groups) {
            val matchedRule = keyToRule[g.groupKey] ?: g.aliasKeys.firstNotNullOfOrNull { keyToRule[it] }
            if (matchedRule == null) {
                out += g
            } else {
                buckets.getOrPut(matchedRule.targetKey) { ArrayList() }.add(g)
            }
        }
        if (buckets.isEmpty()) return groups

        for ((targetKey, members) in buckets) {
            val rule = rulesByTarget[targetKey] ?: continue
            if (rule.targetUrl.isNotBlank()) {
                mergedUrlOverrides[targetKey] = rule.targetUrl
            }
            val primary = members.firstOrNull {
                it.groupKey == rule.targetKey || rule.targetKey in it.aliasKeys
            } ?: members.first()
            val posterOwner = members.firstOrNull {
                (it.groupKey == rule.targetKey || rule.targetKey in it.aliasKeys) && it.hasRealPoster
            } ?: members.firstOrNull { it.hasRealPoster } ?: primary

            val combinedVideos = members.asSequence()
                .flatMap { it.videos.asSequence() }
                .distinctBy { it.video.messageId }
                .sortedWith(TelegramAnimeParser.EPISODE_SORT_COMPARATOR)
                .toList()
            val combinedAliases = linkedSetOf<String>().apply {
                add(rule.targetKey)
                addAll(rule.memberKeys)
                members.forEach { m ->
                    add(m.groupKey)
                    addAll(m.aliasKeys)
                }
            }
            out += primary.copy(
                groupKey = rule.targetKey,
                displayTitle = rule.targetTitle,
                videos = combinedVideos,
                posterFileId = posterOwner.posterFileId,
                posterFallbackThumb = posterOwner.posterFallbackThumb,
                latestMessageId = members.maxOf { it.latestMessageId },
                latestDate = members.maxOf { it.latestDate },
                earliestMessageId = members.minOf { it.earliestMessageId },
                aliasKeys = combinedAliases,
                hasRealPoster = posterOwner.hasRealPoster,
                posterChatId = posterOwner.posterChatId,
                posterMessageId = posterOwner.posterMessageId,
            )
        }
        return out.sortedByDescending { it.latestMessageId }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun matchAnyMergedSeries(
        video: Td.ChatVideo,
        groupKey: String,
        seriesTitle: String,
        targetKind: TelegramAnimeParser.SeriesKind,
        targetSeason: Int,
        aliases: Set<String>,
        extraKeys: Set<String>,
        extraTitles: List<String>,
    ): TelegramAnimeParser.ParsedVideo? {
        TelegramAnimeParser.matchesSeriesVideo(
            video = video,
            targetGroupKey = groupKey,
            targetSeriesTitle = seriesTitle,
            targetKind = targetKind,
            targetSeason = targetSeason,
            aliasKeys = aliases,
        )?.let { return it }

        if (extraKeys.size <= 1 && extraTitles.isEmpty()) return null
        val pv = TelegramAnimeParser.parseVideo(video)
        if (pv.groupKey in extraKeys) {
            return TelegramAnimeParser.ParsedVideo(video, pv)
        }
        for (altTitle in extraTitles) {
            TelegramAnimeParser.matchesSeriesVideo(
                video = video,
                targetGroupKey = groupKey,
                targetSeriesTitle = altTitle,
                targetKind = targetKind,
                targetSeason = targetSeason,
                aliasKeys = aliases,
            )?.let { return it }
        }
        return null
    }

    companion object {
        private const val PAGE_LIMIT = 40
        private const val CATALOGUE_SCAN_MESSAGES = 200
        private const val BACKFILL_SCAN_MESSAGES = 120
        private const val MAX_SCAN_ROUNDS = 3
        private const val EPISODE_SEARCH_LIMIT = 100
        private const val MAX_EPISODE_SEARCH_CHUNKS = 5
        private const val POSTER_SEARCH_CONCURRENCY = 12
        private const val POSTER_SEARCH_TIMEOUT_MS = 1800L
        private const val MIN_ANIMATION_SECS = 3
        private const val MIN_ANIMATION_BYTES = 2L * 1024L * 1024L
        private const val PREF_PREFERRED_QUALITY = "preferred_quality"
        private const val DEFAULT_QUALITY = "hls"
    }
}
