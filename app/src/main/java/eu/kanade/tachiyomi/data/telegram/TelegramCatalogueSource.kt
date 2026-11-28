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
    private val forcePosterRefreshKeys = ConcurrentHashMap.newKeySet<String>()
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

    fun invalidateSeriesPosterCache(animeUrl: String) {
        if (!animeUrl.startsWith("/tg/series")) return
        val groupKey = Uri.parse("https://t.me$animeUrl").getQueryParameter("key").orEmpty()
        if (groupKey.isBlank()) return
        forcePosterRefreshKeys += groupKey
        posterMissKeys.remove(groupKey)
        posterCache.remove(groupKey)
        seriesCache[groupKey]?.let { existing ->
            existing.aliasKeys.forEach { ak ->
                posterMissKeys.remove(ak)
                posterCache.remove(ak)
                forcePosterRefreshKeys += ak
            }
            val reset = existing.copy(hasRealPoster = false)
            seriesCache[groupKey] = reset
            existing.aliasKeys.forEach { ak -> seriesCache[ak] = reset }
        }
    }

    fun cachedAliasesFor(key: String): Set<String> =
        seriesCache[key]?.aliasKeys ?: emptySet()

    fun cachedGroupKeyForMessage(messageId: Long): String? =
        collectedVideos[messageId]?.let { TelegramAnimeParser.parseVideo(it).groupKey }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_QUALITY
            title = "Preferred quality"
            entries = arrayOf("Original (Uploaded quality)")
            entryValues = arrayOf(DEFAULT_QUALITY)
            setDefaultValue(DEFAULT_QUALITY)
            value = DEFAULT_QUALITY
            summary = "%s"
        }.also(screen::addPreference)
    }

    fun preferredQuality(): String = DEFAULT_QUALITY

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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
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

            val forceRefresh = forcePosterRefreshKeys.remove(groupKey)
            val missingThumb = anime.thumbnail_url.isNullOrBlank()
            if (forceRefresh || missingThumb) {
                posterMissKeys.remove(groupKey)
                posterCache.remove(groupKey)
            }
            val cachedSeries = seriesCache[groupKey]
            val cachedPoster = posterCache[groupKey]
            val needsRealPoster = forceRefresh ||
                missingThumb ||
                (cachedPoster == null && cachedSeries?.hasRealPoster != true)
            if (needsRealPoster && chatId != 0L && groupKey !in posterMissKeys && Td.ensureReady(appContext)) {
                Td.ensureChatLoaded(chatId)
                val (matched, completed) = searchPosterWithFallback(
                    chatId = chatId,
                    seriesTitle = seriesTitle,
                    groupKey = groupKey,
                    rawSeason = targetRawSeason,
                    effectiveSeason = targetSeason,
                    kind = targetKind,
                    isFinalSeason = targetIsFinal,
                    timeoutMs = MANUAL_POSTER_SEARCH_TIMEOUT_MS,
                    extraCandidates = collectedImages,
                    allowRecentFetch = forceRefresh || collectedImages.isEmpty(),
                )
                if (matched != null && matched.fileId != 0) {
                    val entryPoster = cacheFoundPoster(
                        groupKey = groupKey,
                        seriesTitle = seriesTitle,
                        targetSeason = targetSeason,
                        targetKind = targetKind,
                        matched = matched,
                        cachedSeries = cachedSeries,
                    )
                    anime.thumbnail_url = TdHttpServer.thumbUrlFor(
                        entryPoster.fileId,
                        entryPoster.chatId,
                        entryPoster.messageId,
                    )
                } else if (completed) {
                    posterMissKeys += groupKey
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
            val isUnmerged = TelegramSeriesMergeStore.isUnmergedEntryUrl(anime.url)
            val umsgIds = uri.getQueryParameter("umsg")
                .orEmpty()
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
            if (isUnmerged && umsgIds.isNotEmpty()) {
                val unmergedList = umsgIds.mapNotNull { mid ->
                    val v = collectedVideos[mid]
                        ?: if (chatId != 0L && Td.ensureReady(appContext)) Td.chatVideoById(chatId, mid) else null
                    v?.takeIf(::isValidEpisodeVideo)?.let {
                        TelegramAnimeParser.ParsedVideo(it, TelegramAnimeParser.parseVideo(it))
                    }
                }
                if (unmergedList.isNotEmpty()) {
                    return buildEpisodeListFromParsed(unmergedList, mergeRule = null)
                }
            }

            val mergeRule = if (isUnmerged) null else TelegramSeriesMergeStore.ruleForKey(telegramPrefs, id, groupKey)
            val excludedIds = mergeRule?.excludedMessageIds ?: emptySet()
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
                    if (isValidEpisodeVideo(pv.video) && pv.video.messageId !in excludedIds) {
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
                            if (isValidEpisodeVideo(pv.video) && pv.video.messageId !in excludedIds) {
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
                        if (!isValidEpisodeVideo(v) || v.messageId in excludedIds || merged.containsKey(v.messageId)) continue
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
                        if (!isValidEpisodeVideo(v) || v.messageId in excludedIds || merged.containsKey(v.messageId)) continue
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
                        if (!isValidEpisodeVideo(v) || v.messageId in excludedIds || merged.containsKey(v.messageId)) continue
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

            if (mergeRule != null) {
                for (v in collectedVideos.values) {
                    if (!isValidEpisodeVideo(v) || v.messageId in excludedIds || merged.containsKey(v.messageId)) continue
                    val pv = TelegramAnimeParser.parseVideo(v)
                    if (pv.groupKey in allMatchKeys || pv.altKeys.any { it in allMatchKeys }) {
                        merged[v.messageId] = TelegramAnimeParser.ParsedVideo(v, pv)
                    }
                }
            }
            return buildEpisodeListFromParsed(merged.values.toList(), mergeRule)
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
                hosterName = "Telegram",
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
                originalOnly = true,
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
            originalOnly = true,
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
                        val (matched, completed) = searchPosterWithFallback(
                            chatId = chatId,
                            seriesTitle = g.seriesTitle,
                            groupKey = g.groupKey,
                            rawSeason = g.rawSeason,
                            effectiveSeason = g.effectiveSeason,
                            kind = g.kind,
                            isFinalSeason = g.isFinalSeason,
                            timeoutMs = POSTER_SEARCH_TIMEOUT_MS,
                            extraCandidates = batchDiscoveredImages,
                            allowRecentFetch = false,
                        )
                        if (matched != null && matched.fileId != 0) {
                            cacheFoundPoster(
                                groupKey = g.groupKey,
                                seriesTitle = g.seriesTitle,
                                targetSeason = g.effectiveSeason,
                                targetKind = g.kind,
                                matched = matched,
                                cachedSeries = g,
                            )
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

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun searchPosterWithFallback(
        chatId: Long,
        seriesTitle: String,
        groupKey: String,
        rawSeason: Int,
        effectiveSeason: Int,
        kind: TelegramAnimeParser.SeriesKind,
        isFinalSeason: Boolean,
        timeoutMs: Long,
        extraCandidates: ConcurrentHashMap<Long, Td.ChatImage>,
        allowRecentFetch: Boolean,
    ): Pair<Td.ChatImage?, Boolean> {
        val exactLocal = (extraCandidates.values + collectedImages.values).firstOrNull { img ->
            TelegramAnimeParser.matchesSeriesPoster(
                targetSeriesTitle = seriesTitle,
                targetGroupKey = groupKey,
                targetKind = kind,
                targetSeason = effectiveSeason,
                image = img,
            ) != null
        }
        if (exactLocal != null) return exactLocal to true

        val chunks = TelegramAnimeParser.buildPosterSearchChunks(
            seriesTitle = seriesTitle,
            rawSeason = rawSeason,
            kind = kind,
            isFinalSeason = isFinalSeason,
        )
        if (chunks.isEmpty()) return null to true

        val searchCandidates = ConcurrentHashMap<Long, Td.ChatImage>()
        var completed = false
        val exactSearch = withTimeoutOrNull(timeoutMs) {
            Td.searchPosterByChunks(chatId, chunks, searchCandidates) { img ->
                collectedImages.putIfAbsent(img.messageId, img)
                extraCandidates.putIfAbsent(img.messageId, img)
                TelegramAnimeParser.matchesSeriesPoster(
                    targetSeriesTitle = seriesTitle,
                    targetGroupKey = groupKey,
                    targetKind = kind,
                    targetSeason = effectiveSeason,
                    image = img,
                ) != null
            }.also { hit ->
                if (hit != null) {
                    collectedImages.putIfAbsent(hit.messageId, hit)
                    extraCandidates.putIfAbsent(hit.messageId, hit)
                }
                completed = true
            }
        }
        if (exactSearch != null) return exactSearch to true

        searchCandidates.forEach { (id, img) ->
            collectedImages.putIfAbsent(id, img)
            extraCandidates.putIfAbsent(id, img)
        }
        if (allowRecentFetch) {
            Td.chatRecentImages(chatId).forEach { img ->
                searchCandidates.putIfAbsent(img.messageId, img)
                collectedImages.putIfAbsent(img.messageId, img)
                extraCandidates.putIfAbsent(img.messageId, img)
            }
            val exactRecent = searchCandidates.values.firstOrNull { img ->
                TelegramAnimeParser.matchesSeriesPoster(
                    targetSeriesTitle = seriesTitle,
                    targetGroupKey = groupKey,
                    targetKind = kind,
                    targetSeason = effectiveSeason,
                    image = img,
                ) != null
            }
            if (exactRecent != null) return exactRecent to true
        }

        // Last resort: use the closest matching image from Telegram search / channel pool
        val pool = (searchCandidates.values + extraCandidates.values + collectedImages.values)
            .distinctBy { it.messageId }
        val closest = TelegramAnimeParser.findClosestPosterMatch(
            targetSeriesTitle = seriesTitle,
            targetGroupKey = groupKey,
            targetKind = kind,
            targetSeason = effectiveSeason,
            candidates = pool,
        )
        return closest to completed
    }

    @Suppress("LongParameterList")
    private fun cacheFoundPoster(
        groupKey: String,
        seriesTitle: String,
        targetSeason: Int,
        targetKind: TelegramAnimeParser.SeriesKind,
        matched: Td.ChatImage,
        cachedSeries: TelegramAnimeParser.GroupedSeries?,
    ): CachedPoster {
        val imgParsed = TelegramAnimeParser.parseImage(matched)
        val bestTitle = TelegramAnimeParser.chooseBestSeriesTitle(seriesTitle, imgParsed?.seriesTitle)
        val bestDisplay = TelegramAnimeParser.formatSeriesDisplayTitle(bestTitle, targetSeason, targetKind)
        val cached = CachedPoster(
            fileId = matched.fileId,
            chatId = matched.chatId,
            messageId = matched.messageId,
            fallbackThumb = matched.minithumb,
            upgradedTitle = bestTitle,
            upgradedDisplayTitle = bestDisplay,
        )
        posterCache[groupKey] = cached
        posterMissKeys.remove(groupKey)
        cachedSeries?.let { s ->
            val upgraded = applyCachedPoster(s, cached)
            seriesCache[groupKey] = upgraded
            upgraded.aliasKeys.forEach { ak ->
                posterCache[ak] = cached
                posterMissKeys.remove(ak)
                seriesCache[ak] = upgraded
            }
        }
        return cached
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
        labelOverride: String? = null,
    ): String {
        val parsed = preParsed ?: TelegramAnimeParser.parseVideo(v)
        val label = labelOverride?.takeIf { it.isNotBlank() } ?: parsed.episodeLabel
        return "/tg/item?mode=td" +
            "&chat=${v.chatId}" +
            "&msg=${v.messageId}" +
            "&file=${v.fileId}" +
            "&w=${v.width}" +
            "&h=${v.height}" +
            "&dur=${v.duration}" +
            "&size=${v.size}" +
            "&date=${v.date}" +
            "&title=${Uri.encode(label)}"
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

            val allVideos = members.asSequence()
                .flatMap { it.videos.asSequence() }
                .distinctBy { it.video.messageId }
                .toList()
            val (excludedVideos, keptVideos) = allVideos.partition { it.video.messageId in rule.excludedMessageIds }
            val combinedVideos = keptVideos.sortedWith(TelegramAnimeParser.EPISODE_SORT_COMPARATOR)
            if (combinedVideos.isNotEmpty()) {
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
                    latestMessageId = combinedVideos.maxOf { it.video.messageId },
                    latestDate = combinedVideos.maxOf { it.video.date },
                    earliestMessageId = combinedVideos.minOf { it.video.messageId },
                    aliasKeys = combinedAliases,
                    hasRealPoster = posterOwner.hasRealPoster,
                    posterChatId = posterOwner.posterChatId,
                    posterMessageId = posterOwner.posterMessageId,
                )
            }
            if (excludedVideos.isNotEmpty()) {
                val splitByKey = excludedVideos.groupBy { it.parsed.groupKey }
                for ((origKey, splitList) in splitByKey) {
                    val sortedSplit = splitList.sortedWith(TelegramAnimeParser.EPISODE_SORT_COMPARATOR)
                    val firstParsed = sortedSplit.first().parsed
                    val splitKey = "unmerged:$origKey:${sortedSplit.first().video.messageId}"
                    val umsgCsv = sortedSplit.joinToString(",") { it.video.messageId.toString() }
                    val splitChatId = sortedSplit.first().video.chatId
                    mergedUrlOverrides[splitKey] = "/tg/series?chat=$splitChatId" +
                        "&key=${Uri.encode(origKey)}" +
                        "&unmerged=1&umsg=${Uri.encode(umsgCsv)}" +
                        "&title=${Uri.encode(firstParsed.seriesTitle)}" +
                        "&disp=${Uri.encode(firstParsed.displayTitle)}" +
                        "&season=${firstParsed.effectiveSeason}" +
                        "&rawSeason=${firstParsed.rawSeason}" +
                        "&final=${firstParsed.isFinalSeason}" +
                        "&kind=${firstParsed.kind.name}"
                    out += TelegramAnimeParser.GroupedSeries(
                        groupKey = splitKey,
                        displayTitle = firstParsed.displayTitle,
                        seriesTitle = firstParsed.seriesTitle,
                        baseKey = firstParsed.baseKey,
                        rawSeason = firstParsed.rawSeason,
                        effectiveSeason = firstParsed.effectiveSeason,
                        kind = firstParsed.kind,
                        videos = sortedSplit,
                        posterFileId = posterOwner.posterFileId,
                        posterFallbackThumb = sortedSplit.first().video.thumb ?: posterOwner.posterFallbackThumb,
                        latestMessageId = sortedSplit.maxOf { it.video.messageId },
                        latestDate = sortedSplit.maxOf { it.video.date },
                        earliestMessageId = sortedSplit.minOf { it.video.messageId },
                        aliasKeys = setOf(splitKey),
                        hasRealPoster = false,
                        posterChatId = posterOwner.posterChatId,
                        posterMessageId = posterOwner.posterMessageId,
                        isFinalSeason = firstParsed.isFinalSeason,
                    )
                }
            }
        }
        return out.sortedByDescending { it.latestMessageId }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildEpisodeListFromParsed(
        videos: List<TelegramAnimeParser.ParsedVideo>,
        mergeRule: TelegramSeriesMergeStore.MergedSeriesRule?,
    ): List<SEpisode> {
        if (videos.isEmpty()) return emptyList()

        data class NumberedEpisode(
            val pv: TelegramAnimeParser.ParsedVideo,
            val epNum: Float,
            val epLabel: String,
        )

        val usedNumbers = HashSet<Float>()
        val knownItems = ArrayList<NumberedEpisode>(videos.size)
        val unknownItems = ArrayList<Pair<TelegramAnimeParser.ParsedVideo, Int>>()

        for (pv in videos) {
            val ep = pv.parsed.episodeNumber
            if (ep != null) {
                usedNumbers += ep
                knownItems += NumberedEpisode(pv, ep, pv.parsed.episodeLabel)
            } else {
                val slot = mergeRule?.let {
                    TelegramSeriesMergeStore.selectionSlotOf(it, pv.parsed.groupKey, pv.parsed.altKeys)
                } ?: Int.MAX_VALUE
                unknownItems += pv to slot
            }
        }

        unknownItems.sortWith(
            Comparator { (a, slotA), (b, slotB) ->
                val slotCmp = slotA.compareTo(slotB)
                if (slotCmp != 0) return@Comparator slotCmp
                val dateCmp = a.video.date.compareTo(b.video.date)
                if (dateCmp != 0) return@Comparator dateCmp
                a.video.messageId.compareTo(b.video.messageId)
            },
        )

        val assignedItems = ArrayList<NumberedEpisode>(videos.size).apply {
            addAll(knownItems)
        }
        var nextSequential = 1
        for ((pv, slot) in unknownItems) {
            var candidate = if (mergeRule != null && slot < Int.MAX_VALUE) slot + 1 else nextSequential
            while (candidate.toFloat() in usedNumbers) {
                candidate++
            }
            val epFloat = candidate.toFloat()
            usedNumbers += epFloat
            if (candidate >= nextSequential) nextSequential = candidate + 1
            val label = TelegramAnimeParser.formatEpisodeLabel(
                epNum = epFloat,
                epEnd = pv.parsed.episodeEnd,
                rawSeason = pv.parsed.rawSeason,
                kind = pv.parsed.kind,
                isFinalSeason = pv.parsed.isFinalSeason,
                variantLabel = null,
            )
            assignedItems += NumberedEpisode(pv, epFloat, label)
        }

        assignedItems.sortWith(
            Comparator { a, b ->
                val seasonA = if (a.pv.parsed.rawSeason > 0) a.pv.parsed.rawSeason else a.pv.parsed.effectiveSeason
                val seasonB = if (b.pv.parsed.rawSeason > 0) b.pv.parsed.rawSeason else b.pv.parsed.effectiveSeason
                val seasonCmp = seasonA.compareTo(seasonB)
                if (seasonCmp != 0) return@Comparator seasonCmp
                val epCmp = a.epNum.compareTo(b.epNum)
                if (epCmp != 0) return@Comparator epCmp
                val dateCmp = a.pv.video.date.compareTo(b.pv.video.date)
                if (dateCmp != 0) return@Comparator dateCmp
                a.pv.video.messageId.compareTo(b.pv.video.messageId)
            },
        )

        return assignedItems.map { item ->
            val v = item.pv.video
            val parsed = item.pv.parsed
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
                url = buildTdItemUrl(v, parsed, item.epLabel)
                name = item.epLabel
                episode_number = item.epNum
                date_upload = v.date.toLong() * 1000L
                scanlator = metaParts.joinToString(" • ").ifBlank { entry.title }
            }
        }.reversed()
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
        private const val POSTER_SEARCH_TIMEOUT_MS = 4500L
        private const val MANUAL_POSTER_SEARCH_TIMEOUT_MS = 8000L
        private const val MIN_ANIMATION_SECS = 3
        private const val MIN_ANIMATION_BYTES = 2L * 1024L * 1024L
        private const val PREF_PREFERRED_QUALITY = "preferred_quality"
        private const val DEFAULT_QUALITY = "original"
    }
}
