package eu.kanade.tachiyomi.ui.home.genre

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.feature.announcements.AnnouncementsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class RankedAnime(
    val anime: Anime,
    val sourceOrderRank: Int,
    val sourceId: Long,
)

class AnimeRecencyResolver(
    private val trackerManager: TrackerManager = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val aniListDateCache = ConcurrentHashMap<String, Long>()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sortNewestFirst(candidates: List<RankedAnime>): List<Anime> {
        if (candidates.isEmpty()) return emptyList()

        // Group by normalized title while preserving richest metadata & earliest source latest-update rank
        val mergedGroups = mergeByNormalizedTitle(candidates)
        val cachedAnnouncements = buildCachedAnnouncementDateMap()

        val withLocalDates = mergedGroups.mapIndexed { index, group ->
            val titleKey = TitleDeduplicator.normalizeTitle(group.primary.title)
            val announcementDate = cachedAnnouncements[titleKey] ?: 0L
            val metadataDate = extractDateFromTexts(group.textsForDateParsing)
            val bestLocalDate = maxOf(announcementDate, metadataDate)
            val aniListCached = if (bestLocalDate > 0L || titleKey.isBlank()) {
                null
            } else {
                aniListDateCache[titleKey]
            }
            val resolvedDate = when {
                bestLocalDate > 0L -> bestLocalDate
                aniListCached != null && aniListCached > 0L -> aniListCached
                else -> 0L
            }
            ResolvedGroup(
                anime = group.primary,
                bestOrderRank = group.bestOrderRank,
                originalIndex = index,
                titleKey = titleKey,
                needsAniList = bestLocalDate <= 0L && aniListCached == null && titleKey.isNotBlank(),
                dateKey = resolvedDate,
            )
        }.toMutableList()

        // Stage 4: Only query AniList as last fallback for titles without any resolved date
        val pendingAniListIndices = withLocalDates.indices
            .filter { withLocalDates[it].needsAniList }
            .take(MAX_ANILIST_LOOKUPS_PER_ROW)

        if (pendingAniListIndices.isNotEmpty()) {
            val queries = pendingAniListIndices
                .map { idx -> withLocalDates[idx].titleKey to withLocalDates[idx].anime.title }
                .distinctBy { it.first }

            val fetched = fetchAniListDatesBatched(queries)
            for (idx in pendingAniListIndices) {
                val entry = withLocalDates[idx]
                val fetchedDate = fetched[entry.titleKey]
                if (fetchedDate != null && fetchedDate > 0L) {
                    withLocalDates[idx] = entry.copy(dateKey = fetchedDate)
                }
            }
        }

        return withLocalDates
            .sortedWith(
                compareByDescending<ResolvedGroup> { it.dateKey > 0L }
                    .thenByDescending { it.dateKey }
                    .thenBy { it.bestOrderRank }
                    .thenBy { it.originalIndex },
            )
            .map { it.anime }
    }

    private fun mergeByNormalizedTitle(candidates: List<RankedAnime>): List<MergedGroup> {
        val grouped = LinkedHashMap<String, MutableList<RankedAnime>>()
        for (item in candidates) {
            val normalized = TitleDeduplicator.normalizeTitle(item.anime.title)
            val key = if (normalized.isBlank()) item.anime.title.trim().lowercase() else normalized
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        return grouped.values.map { list ->
            val bestPrimary = list.maxWithOrNull(
                compareBy<RankedAnime> { !it.anime.description.isNullOrBlank() }
                    .thenBy { !it.anime.thumbnailUrl.isNullOrBlank() }
                    .thenByDescending { -it.sourceOrderRank },
            )?.anime ?: list.first().anime

            val bestOrderRank = list.minOf { it.sourceOrderRank }
            val texts = buildList {
                add(bestPrimary.title)
                bestPrimary.description?.let { add(it) }
                list.forEach { ranked ->
                    if (ranked.anime.title != bestPrimary.title) {
                        add(ranked.anime.title)
                    }
                    ranked.anime.description?.takeIf { it.isNotBlank() && it != bestPrimary.description }?.let {
                        add(it)
                    }
                }
            }
            MergedGroup(
                primary = bestPrimary,
                bestOrderRank = bestOrderRank,
                textsForDateParsing = texts,
            )
        }
    }

    private fun buildCachedAnnouncementDateMap(): Map<String, Long> {
        val cached = try {
            AnnouncementsRepository().getCached().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (cached.isEmpty()) return emptyMap()

        val map = HashMap<String, Long>(cached.size)
        for (entry in cached) {
            val key = TitleDeduplicator.normalizeTitle(entry.title)
            if (key.isBlank()) continue
            val dateKey = when {
                entry.exactReleaseDate != null -> {
                    runCatching {
                        val date = Instant.ofEpochMilli(entry.exactReleaseDate)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        date.year * 10000L + date.monthValue * 100L + date.dayOfMonth
                    }.getOrDefault(0L)
                }
                entry.expectedYear != null -> entry.expectedYear * 10000L
                else -> 0L
            }
            if (dateKey > 0L) {
                map[key] = maxOf(map[key] ?: 0L, dateKey)
            }
        }
        return map
    }

    private suspend fun fetchAniListDatesBatched(
        queries: List<Pair<String, String>>,
    ): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val maxPlausibleYear = Calendar.getInstance().get(Calendar.YEAR) + 2

        for (chunk in queries.chunked(ANILIST_BATCH_SIZE)) {
            val chunkResult = withTimeoutOrNull(ANILIST_TIMEOUT_MS) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val varDefs = chunk.indices.joinToString(", ") { "\$q$it: String" }
                        val aliasBlocks = chunk.indices.joinToString("\n") { i ->
                            """
                            m$i: Page(page: 1, perPage: 1) {
                                media(search: ${'$'}q$i, type: ANIME, sort: [SEARCH_MATCH]) {
                                    startDate { year month day }
                                    seasonYear
                                }
                            }
                            """.trimIndent()
                        }
                        val gqlQuery = "query ($varDefs) {\n$aliasBlocks\n}"
                        val variables = buildJsonObject {
                            chunk.forEachIndexed { idx, (_, rawTitle) ->
                                put("q$idx", cleanSearchTitle(rawTitle))
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
                                return@runCatching emptyMap<String, Long>()
                            }
                            val root = json.parseToJsonElement(response.body.string()).jsonObject
                            val dataObj = root["data"]?.jsonObject ?: return@runCatching emptyMap()
                            val parsedMap = mutableMapOf<String, Long>()
                            chunk.forEachIndexed { idx, (key, _) ->
                                val mediaArray = dataObj["m$idx"]
                                    ?.jsonObject
                                    ?.get("media")
                                    as? kotlinx.serialization.json.JsonArray
                                val mediaObj = mediaArray?.firstOrNull()?.jsonObject
                                val startDateObj = mediaObj?.get("startDate")?.jsonObject
                                val year = startDateObj?.get("year")?.jsonPrimitive?.intOrNull
                                    ?: mediaObj?.get("seasonYear")?.jsonPrimitive?.intOrNull
                                val month = startDateObj?.get("month")?.jsonPrimitive?.intOrNull?.coerceIn(1, 12) ?: 0
                                val day = startDateObj?.get("day")?.jsonPrimitive?.intOrNull?.coerceIn(1, 31) ?: 0

                                if (year != null && year in MIN_PLAUSIBLE_YEAR..maxPlausibleYear) {
                                    val dateKey = year * 10000L + month * 100L + day
                                    aniListDateCache[key] = dateKey
                                    parsedMap[key] = dateKey
                                } else {
                                    aniListDateCache[key] = CACHE_MISS_SENTINEL
                                }
                            }
                            parsedMap
                        }
                    }.onFailure { e ->
                        logcat(LogPriority.DEBUG, e) { "AniList recency fallback lookup skipped" }
                    }.getOrDefault(emptyMap())
                }
            } ?: emptyMap()

            result.putAll(chunkResult)
        }
        return result
    }

    private data class MergedGroup(
        val primary: Anime,
        val bestOrderRank: Int,
        val textsForDateParsing: List<String>,
    )

    private data class ResolvedGroup(
        val anime: Anime,
        val bestOrderRank: Int,
        val originalIndex: Int,
        val titleKey: String,
        val needsAniList: Boolean,
        val dateKey: Long,
    )

    companion object {
        private const val MIN_PLAUSIBLE_YEAR = 1960
        private const val CACHE_MISS_SENTINEL = -1L
        private const val MAX_ANILIST_LOOKUPS_PER_ROW = 24
        private const val ANILIST_BATCH_SIZE = 10
        private const val ANILIST_TIMEOUT_MS = 6_000L

        private val SORT_CONTROL_NAME_REGEX = Regex(
            "\\b(?:sort(?:\\s*by|\\s*order)?|order(?:\\s*by)?|arrange|ranking)\\b",
            RegexOption.IGNORE_CASE,
        )

        private val DISALLOWED_SORT_OPTIONS = setOf(
            "oldest",
            "a-z",
            "a - z",
            "z-a",
            "z - a",
            "alphabet",
            "alphabetical",
            "title",
            "name",
            "least",
        )

        private val PREFERRED_SORT_TIERS = listOf(
            listOf("newest", "latest", "recently added", "recently updated", "recent", "just added", "new"),
            listOf("release date", "release year", "released", "release", "air date", "aired", "year", "date"),
            listOf("updated", "update", "last update"),
        )

        private val ISO_DATE_REGEX = Regex("\\b(19[6-9]\\d|20[0-3]\\d)[-/](0?[1-9]|1[0-2])[-/](0?[1-9]|[12]\\d|3[01])\\b")
        private val MONTH_DAY_YEAR_REGEX = Regex(
            "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2}),?\\s+(19[6-9]\\d|20[0-3]\\d)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val MONTH_YEAR_REGEX = Regex(
            "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(19[6-9]\\d|20[0-3]\\d)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val SEASON_YEAR_REGEX = Regex(
            "\\b(winter|spring|summer|fall|autumn)\\s+(19[6-9]\\d|20[0-3]\\d)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val LABELED_YEAR_REGEX = Regex(
            "\\b(?:released?|aired|premiered|broadcast|year|started|release\\s*date)\\s*[:\\-]?\\s*(19[6-9]\\d|20[0-3]\\d)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val BRACKETED_YEAR_REGEX = Regex("(?:\\(|\\[)\\s*(19[6-9]\\d|20[0-3]\\d)\\s*(?:\\)|\\])")
        private val STANDALONE_YEAR_REGEX = Regex("\\b(19[7-9]\\d|20[0-3]\\d)\\b(?!(?:p|k|fps|mb|gb|ep|episodes?)\\b)")
        private val PAREN_CLEANUP_REGEX = Regex(
            "\\s*(?:\\(|\\[)(?:tv|sub|dub|uncensored|censored|1080p|720p|480p|bd|ova|ona)(?:\\)|\\])\\s*",
            RegexOption.IGNORE_CASE,
        )

        fun isSortControlName(name: String): Boolean {
            return SORT_CONTROL_NAME_REGEX.containsMatchIn(name.trim())
        }

        /**
         * Stage 1: Mutates any Sort / sort-Select filters on [filters] toward newest/latest/release date
         * descending without touching genre/category/theme filters.
         */
        fun applyLatestSortFilter(filters: AnimeFilterList) {
            applySortRecursive(filters)
        }

        private fun applySortRecursive(items: List<*>) {
            for (item in items) {
                when (item) {
                    is AnimeFilter.Sort -> {
                        val bestIdx = findBestLatestOptionIndex(item.values)
                        if (bestIdx >= 0) {
                            item.state = AnimeFilter.Sort.Selection(
                                index = bestIdx,
                                ascending = false,
                            )
                        }
                    }
                    is AnimeFilter.Select<*> -> {
                        if (isSortControlName(item.name)) {
                            val bestIdx = findBestLatestOptionIndex(item.values)
                            if (bestIdx >= 0) {
                                item.state = bestIdx
                            }
                        }
                    }
                    is AnimeFilter.Group<*> -> {
                        applySortRecursive(item.state)
                    }
                    else -> {}
                }
            }
        }

        private fun findBestLatestOptionIndex(values: Array<*>): Int {
            for (keywords in PREFERRED_SORT_TIERS) {
                val idx = values.indexOfFirst { option ->
                    val label = option?.toString()?.trim()?.lowercase() ?: return@indexOfFirst false
                    if (DISALLOWED_SORT_OPTIONS.any { bad -> label.contains(bad) }) {
                        return@indexOfFirst false
                    }
                    keywords.any { kw -> label.contains(kw) }
                }
                if (idx >= 0) return idx
            }
            return -1
        }

        fun extractDateFromTexts(texts: List<String>): Long {
            val maxYear = LocalDate.now().year + 2
            var bestDate = 0L

            for (text in texts) {
                if (text.isBlank()) continue

                ISO_DATE_REGEX.findAll(text).forEach { m ->
                    val y = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val mo = m.groupValues[2].toIntOrNull() ?: 0
                    val d = m.groupValues[3].toIntOrNull() ?: 0
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L + mo * 100L + d)
                    }
                }

                MONTH_DAY_YEAR_REGEX.findAll(text).forEach { m ->
                    val mo = monthToNumber(m.groupValues[1])
                    val d = m.groupValues[2].toIntOrNull()?.coerceIn(1, 31) ?: 0
                    val y = m.groupValues[3].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L + mo * 100L + d)
                    }
                }

                MONTH_YEAR_REGEX.findAll(text).forEach { m ->
                    val mo = monthToNumber(m.groupValues[1])
                    val y = m.groupValues[2].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L + mo * 100L)
                    }
                }

                SEASON_YEAR_REGEX.findAll(text).forEach { m ->
                    val mo = seasonToMonth(m.groupValues[1])
                    val y = m.groupValues[2].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L + mo * 100L)
                    }
                }

                LABELED_YEAR_REGEX.findAll(text).forEach { m ->
                    val y = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L)
                    }
                }

                BRACKETED_YEAR_REGEX.findAll(text).forEach { m ->
                    val y = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L)
                    }
                }
            }

            if (bestDate > 0L) return bestDate

            // Standalone plausible year in title or short metadata
            for (text in texts) {
                if (text.isBlank()) continue
                STANDALONE_YEAR_REGEX.findAll(text).forEach { m ->
                    val y = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (y in MIN_PLAUSIBLE_YEAR..maxYear) {
                        bestDate = maxOf(bestDate, y * 10000L)
                    }
                }
            }

            return bestDate
        }

        private fun cleanSearchTitle(title: String): String {
            val cleaned = title.replace(PAREN_CLEANUP_REGEX, " ").trim()
            return cleaned.ifBlank { title.trim() }.take(80)
        }

        private fun monthToNumber(month: String): Int {
            return when (month.lowercase().take(3)) {
                "jan" -> 1
                "feb" -> 2
                "mar" -> 3
                "apr" -> 4
                "may" -> 5
                "jun" -> 6
                "jul" -> 7
                "aug" -> 8
                "sep" -> 9
                "oct" -> 10
                "nov" -> 11
                "dec" -> 12
                else -> 0
            }
        }

        private fun seasonToMonth(season: String): Int {
            return when (season.lowercase()) {
                "winter" -> 1
                "spring" -> 4
                "summer" -> 7
                "fall", "autumn" -> 10
                else -> 0
            }
        }
    }
}
