package eu.kanade.tachiyomi.ui.home.genre

import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.toDomainAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

data class LatestGenreSnapshot(
    val discoveredGenres: List<DiscoveredGenre>,
    val rowsByGenreKey: Map<String, List<Anime>>,
    val latestMatchesByTitle: Map<String, List<Anime>>,
)

class LatestGenreEngine(
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val latestPageCache = ConcurrentHashMap<Long, Pair<Long, List<CachedSourceEntry>>>()
    private val titleTagsCache = ConcurrentHashMap<String, List<String>>()
    private val aniListMissKeys = ConcurrentHashMap.newKeySet<String>()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val sourceFetchLimiter = Semaphore(4)
    private val aniListLimiter = Semaphore(2)
    private val detailsLimiter = Semaphore(4)

    fun clearCaches() {
        latestPageCache.clear()
        aniListMissKeys.clear()
        titleTagsCache.clear()
    }

    fun evictSource(sourceId: Long) {
        latestPageCache.remove(sourceId)
    }

    suspend fun buildLatestGenreFeed(
        sources: List<AnimeCatalogueSource>,
        prioritySourceIds: List<Long>,
        forceRefresh: Boolean,
        dbWriteMutex: Mutex,
        onProgress: suspend (LatestGenreSnapshot) -> Unit,
    ): LatestGenreSnapshot = withContext(Dispatchers.IO) {
        // 1. Discover filter-based genres/categories/themes in parallel with latest updates
        val filterGenresDeferred = async {
            GenreDiscoveryEngine.discoverGenresForSourcesAsync(sources)
        }

        // 2. Fetch latest section pages from all feed sources once
        val perSourceEntries = sources.map { source ->
            async {
                sourceFetchLimiter.withPermit {
                    fetchLatestForSource(source, forceRefresh, dbWriteMutex)
                }
            }
        }.awaitAll().flatten()

        val filterGenres = filterGenresDeferred.await()

        if (perSourceEntries.isEmpty()) {
            return@withContext LatestGenreSnapshot(
                discoveredGenres = filterGenres,
                rowsByGenreKey = emptyMap(),
                latestMatchesByTitle = emptyMap(),
            )
        }

        // 3. Deduplicate across sources by normalized title BEFORE heavy metadata calls
        val groups = deduplicateLatestGroups(perSourceEntries, prioritySourceIds)
        val latestMatchesByTitle = HashMap<String, List<Anime>>(groups.size)
        groups.forEach { group ->
            latestMatchesByTitle[group.titleKey] = group.candidates.map { it.anime }
        }

        val resolvedTagsByTitle = ConcurrentHashMap<String, List<String>>()

        // Tier 1: Instant 0ms tags from session cache, SAnime list items, or SQLite DB
        for (group in groups) {
            val cached = titleTagsCache[group.titleKey]
            val localTags = group.candidates
                .flatMap { it.anime.genre.orEmpty() }
                .filter { it.isNotBlank() }
                .distinct()

            val merged = when {
                !cached.isNullOrEmpty() && localTags.isNotEmpty() -> (cached + localTags).distinct()
                localTags.isNotEmpty() -> localTags
                !cached.isNullOrEmpty() -> cached
                else -> emptyList()
            }
            if (merged.isNotEmpty()) {
                resolvedTagsByTitle[group.titleKey] = merged
                titleTagsCache[group.titleKey] = merged
            }
        }

        val tier1Snapshot = assembleSnapshot(groups, resolvedTagsByTitle, filterGenres, latestMatchesByTitle)
        if (resolvedTagsByTitle.isNotEmpty()) {
            onProgress(tier1Snapshot)
        }

        // Tier 2: Fast batched AniList genres + high-rank tags (~350ms for up to 40 missing titles)
        val missingAfterTier1 = groups.filter {
            !resolvedTagsByTitle.containsKey(it.titleKey) && !aniListMissKeys.contains(it.titleKey)
        }.take(MAX_ANILIST_TITLES)

        if (missingAfterTier1.isNotEmpty()) {
            val aniListResults = fetchAniListGenresBatch(missingAfterTier1)
            val updatesToPersist = mutableListOf<Pair<Anime, List<String>>>()

            for (group in missingAfterTier1) {
                val tags = aniListResults[group.titleKey]
                if (!tags.isNullOrEmpty()) {
                    resolvedTagsByTitle[group.titleKey] = tags
                    titleTagsCache[group.titleKey] = tags
                    group.candidates.forEach { cand ->
                        if (cand.anime.genre.isNullOrEmpty()) {
                            updatesToPersist.add(cand.anime to tags)
                        }
                    }
                } else {
                    aniListMissKeys.add(group.titleKey)
                }
            }

            persistGenresSafely(updatesToPersist, dbWriteMutex)

            if (aniListResults.isNotEmpty()) {
                onProgress(assembleSnapshot(groups, resolvedTagsByTitle, filterGenres, latestMatchesByTitle))
            }
        }

        // Tier 3: Targeted source.getAnimeDetails fallback ONLY for titles still missing tags
        val missingAfterTier2 = groups
            .filter { !resolvedTagsByTitle.containsKey(it.titleKey) }
            .take(MAX_SOURCE_DETAIL_FALLBACKS)

        if (missingAfterTier2.isNotEmpty()) {
            val updatesToPersist = mutableListOf<Pair<Anime, List<String>>>()
            val detailResults = coroutineScope {
                missingAfterTier2.map { group ->
                    async {
                        detailsLimiter.withPermit {
                            ensureActive()
                            val candidate = group.primaryCandidate
                            val source = candidate.source
                            val tags = withTimeoutOrNull(DETAILS_TIMEOUT_MS) {
                                runCatching {
                                    val details = source.getAnimeDetails(candidate.sAnime)
                                    details.getGenres().orEmpty().filter { it.isNotBlank() }
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                            group to tags
                        }
                    }
                }.awaitAll()
            }

            var anyNew = false
            for ((group, tags) in detailResults) {
                if (tags.isNotEmpty()) {
                    anyNew = true
                    resolvedTagsByTitle[group.titleKey] = tags
                    titleTagsCache[group.titleKey] = tags
                    group.candidates.forEach { cand ->
                        if (cand.anime.genre.isNullOrEmpty()) {
                            updatesToPersist.add(cand.anime to tags)
                        }
                    }
                }
            }

            persistGenresSafely(updatesToPersist, dbWriteMutex)

            if (anyNew) {
                val finalSnapshot = assembleSnapshot(groups, resolvedTagsByTitle, filterGenres, latestMatchesByTitle)
                onProgress(finalSnapshot)
                return@withContext finalSnapshot
            }
        }

        assembleSnapshot(groups, resolvedTagsByTitle, filterGenres, latestMatchesByTitle)
    }

    private suspend fun fetchLatestForSource(
        source: AnimeCatalogueSource,
        forceRefresh: Boolean,
        dbWriteMutex: Mutex,
    ): List<RawLatestEntry> {
        val now = System.currentTimeMillis()
        val cached = latestPageCache[source.id]
        val sourceEntries = if (!forceRefresh && cached != null && (now - cached.first < LATEST_CACHE_TTL_MS)) {
            cached.second
        } else {
            val fetchedSAnime = withTimeoutOrNull(SOURCE_LATEST_TIMEOUT_MS) {
                runCatching {
                    val page1 = if (source.supportsLatest) {
                        source.getLatestUpdates(1)
                    } else {
                        source.getPopularAnime(1)
                    }
                    page1.animes.take(MAX_ITEMS_PER_SOURCE)
                }.onFailure { e ->
                    logcat(LogPriority.WARN, e) { "Latest fetch failed for ${source.name}" }
                }.getOrDefault(emptyList())
            }.orEmpty()

            val mappedLocal = dbWriteMutex.withLock {
                fetchedSAnime.map { sAnime ->
                    val local = networkToLocalAnime.await(sAnime.toDomainAnime(source.id))
                    CachedSourceEntry(anime = local, sAnime = sAnime)
                }
            }
            if (mappedLocal.isNotEmpty()) {
                latestPageCache[source.id] = now to mappedLocal
            }
            mappedLocal
        }

        return sourceEntries.mapIndexed { idx, entry ->
            RawLatestEntry(
                anime = entry.anime,
                sAnime = entry.sAnime,
                source = source,
                sourceOrderRank = idx,
            )
        }
    }

    private fun deduplicateLatestGroups(
        entries: List<RawLatestEntry>,
        prioritySourceIds: List<Long>,
    ): List<UniqueLatestGroup> {
        val grouped = LinkedHashMap<String, MutableList<RawLatestEntry>>()
        for (entry in entries) {
            val key = TitleDeduplicator.normalizeTitle(entry.anime.title)
            val effectiveKey = key.ifBlank { entry.anime.title.trim().lowercase() }
            if (effectiveKey.isBlank()) continue
            grouped.getOrPut(effectiveKey) { mutableListOf() }.add(entry)
        }

        val groups = grouped.entries.mapIndexed { seenIdx, (titleKey, list) ->
            val bestLatestRank = list.minOf { it.sourceOrderRank }
            val primary = choosePrimaryCandidate(list, prioritySourceIds)
            UniqueLatestGroup(
                titleKey = titleKey,
                primaryCandidate = primary,
                candidates = list,
                bestLatestRank = bestLatestRank,
                seenOrder = seenIdx,
            )
        }

        // Preserve strict source Latest-section ordering across all sources
        return groups.sortedWith(
            compareBy<UniqueLatestGroup> { it.bestLatestRank }
                .thenBy { it.seenOrder },
        )
    }

    private fun choosePrimaryCandidate(
        candidates: List<RawLatestEntry>,
        prioritySourceIds: List<Long>,
    ): RawLatestEntry {
        for (priorityId in prioritySourceIds) {
            candidates.firstOrNull { it.source.id == priorityId }?.let { return it }
        }
        return candidates.firstOrNull { !it.anime.genre.isNullOrEmpty() }
            ?: candidates.minByOrNull { it.sourceOrderRank }
            ?: candidates.first()
    }

    private suspend fun fetchAniListGenresBatch(
        groups: List<UniqueLatestGroup>,
    ): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()

        coroutineScope {
            val deferredChunks = groups.chunked(ANILIST_BATCH_SIZE).map { chunk ->
                async {
                    aniListLimiter.withPermit {
                        withTimeoutOrNull(ANILIST_TIMEOUT_MS) {
                            runCatching {
                                val varDefs = chunk.indices.joinToString(", ") { "\$q$it: String" }
                                val aliasBlocks = chunk.indices.joinToString("\n") { i ->
                                    """
                                    m$i: Page(page: 1, perPage: 1) {
                                        media(search: ${'$'}q$i, type: ANIME, sort: [SEARCH_MATCH]) {
                                            genres
                                            tags {
                                                name
                                                rank
                                                isMediaSpoiler
                                            }
                                        }
                                    }
                                    """.trimIndent()
                                }
                                val gqlQuery = "query ($varDefs) {\n$aliasBlocks\n}"
                                val variables = buildJsonObject {
                                    chunk.forEachIndexed { idx, group ->
                                        put("q$idx", cleanSearchTitle(group.primaryCandidate.anime.title))
                                    }
                                }
                                val bodyJson = buildJsonObject {
                                    put("query", gqlQuery)
                                    put("variables", variables)
                                }.toString()

                                val request = Request.Builder()
                                    .url("https://graphql.anilist.co")
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .post(bodyJson.toRequestBody(jsonMediaType))
                                    .build()

                                trackerManager.aniList.api.newPublicCall(request).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        return@runCatching emptyMap<String, List<String>>()
                                    }
                                    val root = json.parseToJsonElement(response.body.string()).jsonObject
                                    val dataObj = root["data"]?.jsonObject ?: return@runCatching emptyMap()
                                    val parsed = mutableMapOf<String, List<String>>()

                                    chunk.forEachIndexed { idx, group ->
                                        val mediaArray = dataObj["m$idx"]?.jsonObject?.get("media") as? JsonArray
                                        val mediaObj = mediaArray?.firstOrNull()?.jsonObject ?: return@forEachIndexed

                                        val genresList = mediaObj["genres"]
                                            ?.let { el ->
                                                (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                                            }
                                            .orEmpty()

                                        val tagsList = mediaObj["tags"]
                                            ?.let { el ->
                                                (el as? JsonArray)?.mapNotNull { tagEl ->
                                                    val tagObj = tagEl.jsonObject
                                                    val isSpoiler = tagObj["isMediaSpoiler"]?.jsonPrimitive?.booleanOrNull == true
                                                    val rank = tagObj["rank"]?.jsonPrimitive?.intOrNull ?: 0
                                                    val name = tagObj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                                                    if (!isSpoiler && rank >= MIN_TAG_RANK && !name.isNullOrBlank()) {
                                                        rank to name
                                                    } else {
                                                        null
                                                    }
                                                }?.sortedByDescending { it.first }
                                                    ?.take(MAX_TAGS_PER_ANIME)
                                                    ?.map { it.second }
                                            }
                                            .orEmpty()

                                        val combined = (genresList + tagsList)
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                        if (combined.isNotEmpty()) {
                                            parsed[group.titleKey] = combined
                                        }
                                    }
                                    parsed
                                }
                            }.onFailure { e ->
                                logcat(LogPriority.DEBUG, e) { "AniList batch tag lookup skipped" }
                            }.getOrDefault(emptyMap())
                        }.orEmpty()
                    }
                }
            }

            deferredChunks.awaitAll().forEach { chunkMap ->
                out.putAll(chunkMap)
            }
        }
        return out
    }

    private suspend fun persistGenresSafely(
        updates: List<Pair<Anime, List<String>>>,
        dbWriteMutex: Mutex,
    ) {
        if (updates.isEmpty()) return
        dbWriteMutex.withLock {
            for ((anime, tags) in updates) {
                runCatching {
                    updateAnime.await(
                        AnimeUpdate(
                            id = anime.id,
                            genre = tags,
                        ),
                    )
                }
            }
        }
    }

    private fun assembleSnapshot(
        groups: List<UniqueLatestGroup>,
        resolvedTagsByTitle: Map<String, List<String>>,
        filterGenres: List<DiscoveredGenre>,
        latestMatchesByTitle: Map<String, List<Anime>>,
    ): LatestGenreSnapshot {
        val rowsMap = LinkedHashMap<String, MutableList<Anime>>()
        val displayNames = HashMap<String, String>()

        for (group in groups) {
            val rawTags = resolvedTagsByTitle[group.titleKey].orEmpty()
            if (rawTags.isEmpty()) continue

            val seenGenreKeysForTitle = HashSet<String>()
            for (rawTag in rawTags) {
                val trimmed = rawTag.trim()
                if (trimmed.isBlank()) continue
                val key = GenreNormalizer.toKey(trimmed)
                if (key.isBlank() || !seenGenreKeysForTitle.add(key)) continue

                rowsMap.getOrPut(key) { mutableListOf() }.add(group.primaryCandidate.anime)
                if (!displayNames.containsKey(key)) {
                    displayNames[key] = GenreNormalizer.toDisplayName(trimmed, key)
                }
            }
        }

        val bindingsByKey = filterGenres.associateBy { it.key }
        val allKeys = (rowsMap.keys + bindingsByKey.keys).distinct()

        val discovered = allKeys.map { key ->
            val filterMatch = bindingsByKey[key]
            val count = rowsMap[key]?.size ?: 0
            DiscoveredGenre(
                key = key,
                displayName = displayNames[key] ?: filterMatch?.displayName ?: GenreNormalizer.toDisplayName(key),
                sourceBindings = filterMatch?.sourceBindings ?: emptyList(),
                animeCount = count,
            )
        }.sortedWith(
            compareByDescending<DiscoveredGenre> { it.animeCount > 0 }
                .thenByDescending { it.animeCount }
                .thenBy { it.displayName },
        )

        val cappedRowsMap = rowsMap.mapValues { it.value.take(MAX_GENRE_ROW_TITLES) }

        return LatestGenreSnapshot(
            discoveredGenres = discovered,
            rowsByGenreKey = cappedRowsMap,
            latestMatchesByTitle = latestMatchesByTitle,
        )
    }

    private data class CachedSourceEntry(
        val anime: Anime,
        val sAnime: SAnime,
    )

    private data class RawLatestEntry(
        val anime: Anime,
        val sAnime: SAnime,
        val source: AnimeCatalogueSource,
        val sourceOrderRank: Int,
    )

    private data class UniqueLatestGroup(
        val titleKey: String,
        val primaryCandidate: RawLatestEntry,
        val candidates: List<RawLatestEntry>,
        val bestLatestRank: Int,
        val seenOrder: Int,
    )

    companion object {
        private const val MAX_GENRE_ROW_TITLES = 30
        private const val LATEST_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val SOURCE_LATEST_TIMEOUT_MS = 8_000L
        private const val ANILIST_TIMEOUT_MS = 4_000L
        private const val DETAILS_TIMEOUT_MS = 2_500L
        private const val TARGET_ITEMS_PER_SOURCE = 20
        private const val MAX_ITEMS_PER_SOURCE = 24
        private const val ANILIST_BATCH_SIZE = 10
        private const val MAX_ANILIST_TITLES = 40
        private const val MAX_SOURCE_DETAIL_FALLBACKS = 4
        private const val MIN_TAG_RANK = 65
        private const val MAX_TAGS_PER_ANIME = 5

        private val TITLE_CLEANUP_REGEX = Regex(
            "\\s*(?:\\(|\\[)(?:tv|sub|dub|subbed|dubbed|uncensored|censored|1080p|720p|480p|bd|ova|ona)(?:\\)|\\])\\s*",
            RegexOption.IGNORE_CASE,
        )

        private fun cleanSearchTitle(title: String): String {
            val cleaned = title.replace(TITLE_CLEANUP_REGEX, " ").trim()
            return cleaned.ifBlank { title.trim() }.take(80)
        }
    }
}
