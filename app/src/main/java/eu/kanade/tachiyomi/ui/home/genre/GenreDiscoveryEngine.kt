package eu.kanade.tachiyomi.ui.home.genre

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object GenreDiscoveryEngine {
    private val GENRE_KEYWORDS = listOf(
        "genre",
        "genres",
        "tag",
        "tags",
        "theme",
        "themes",
        "demographic",
        "demographics",
        "subgenre",
        "subgenres",
    )

    private val EXCLUDED_OPTION_NAMES = setOf(
        "all",
        "any",
        "none",
        "choose",
        "select",
        "default",
        "everything",
        "reset",
        "clear",
        "-",
        "--",
        "---",
    )

    private val discoveryLimiter = Semaphore(4)

    fun isGenreLikeGroupOrFilterName(name: String): Boolean {
        val lower = name.lowercase().trim()
        if (lower.isBlank()) return false
        return GENRE_KEYWORDS.any { keyword ->
            lower == keyword ||
                lower.contains(" $keyword") ||
                lower.contains("$keyword ") ||
                lower.contains(keyword)
        }
    }

    suspend fun discoverGenresForSourcesAsync(
        sources: List<AnimeCatalogueSource>,
    ): List<DiscoveredGenre> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val perSourceFilters = coroutineScope {
            sources.map { source ->
                async {
                    discoveryLimiter.withPermit {
                        withTimeoutOrNull(12_000L) {
                            try {
                                source to source.getFilterList()
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) {
                                    "Failed to get live filter list from ${source.name}"
                                }
                                null
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        buildMergedGenres(perSourceFilters)
    }

    fun discoverGenresForSources(sources: List<AnimeCatalogueSource>): List<DiscoveredGenre> {
        val perSourceFilters = sources.mapNotNull { source ->
            try {
                source to source.getFilterList()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to get filter list from ${source.name}" }
                null
            }
        }
        return buildMergedGenres(perSourceFilters)
    }

    private fun buildMergedGenres(
        sourceFilterPairs: List<Pair<AnimeCatalogueSource, AnimeFilterList>>,
    ): List<DiscoveredGenre> {
        val genreMap = mutableMapOf<String, MutableList<SourceGenreBinding>>()
        val displayNames = mutableMapOf<String, String>()

        for ((source, filterList) in sourceFilterPairs) {
            traverseFilterItems(
                source = source,
                items = filterList,
                groupPath = emptyList(),
                inGenreSection = false,
                genreMap = genreMap,
                displayNames = displayNames,
            )
        }

        return genreMap.map { (key, bindings) ->
            DiscoveredGenre(
                key = key,
                displayName = displayNames[key] ?: GenreNormalizer.toDisplayName(key),
                sourceBindings = bindings.distinctBy { it.sourceId },
            )
        }.sortedBy { it.displayName }
    }

    private fun traverseFilterItems(
        source: AnimeCatalogueSource,
        items: List<*>,
        groupPath: List<String>,
        inGenreSection: Boolean,
        genreMap: MutableMap<String, MutableList<SourceGenreBinding>>,
        displayNames: MutableMap<String, String>,
    ) {
        var headerGenreActive = inGenreSection

        for (item in items) {
            when (item) {
                is AnimeFilter.Header -> {
                    headerGenreActive = inGenreSection || isGenreLikeGroupOrFilterName(item.name)
                }
                is AnimeFilter.Separator -> {
                    if (!inGenreSection) {
                        headerGenreActive = false
                    }
                }
                is AnimeFilter.Group<*> -> {
                    val groupMatches = inGenreSection || isGenreLikeGroupOrFilterName(item.name)
                    traverseFilterItems(
                        source = source,
                        items = item.state,
                        groupPath = groupPath + item.name,
                        inGenreSection = groupMatches,
                        genreMap = genreMap,
                        displayNames = displayNames,
                    )
                }
                is AnimeFilter.Select<*> -> {
                    if (headerGenreActive || isGenreLikeGroupOrFilterName(item.name)) {
                        item.values.forEachIndexed { index, option ->
                            val rawName = option?.toString() ?: return@forEachIndexed
                            val lower = rawName.lowercase().trim()
                            if (lower in EXCLUDED_OPTION_NAMES || lower.startsWith("all ")) {
                                return@forEachIndexed
                            }
                            registerOption(
                                source = source,
                                rawName = rawName,
                                applier = GenreFilterApplier.SelectOption(groupPath, item.name, index, rawName),
                                genreMap = genreMap,
                                displayNames = displayNames,
                            )
                        }
                    }
                }
                is AnimeFilter.CheckBox -> {
                    if (headerGenreActive || isGenreLikeGroupOrFilterName(item.name)) {
                        val applier = if (groupPath.isEmpty()) {
                            GenreFilterApplier.TopLevelCheckBox(item.name)
                        } else {
                            GenreFilterApplier.GroupCheckBox(groupPath, item.name)
                        }
                        registerOption(
                            source = source,
                            rawName = item.name,
                            applier = applier,
                            genreMap = genreMap,
                            displayNames = displayNames,
                        )
                    }
                }
                is AnimeFilter.TriState -> {
                    if (headerGenreActive || isGenreLikeGroupOrFilterName(item.name)) {
                        val applier = if (groupPath.isEmpty()) {
                            GenreFilterApplier.TopLevelTriState(item.name)
                        } else {
                            GenreFilterApplier.GroupTriState(groupPath, item.name)
                        }
                        registerOption(
                            source = source,
                            rawName = item.name,
                            applier = applier,
                            genreMap = genreMap,
                            displayNames = displayNames,
                        )
                    }
                }
                else -> {}
            }
        }
    }

    private fun registerOption(
        source: AnimeCatalogueSource,
        rawName: String,
        applier: GenreFilterApplier,
        genreMap: MutableMap<String, MutableList<SourceGenreBinding>>,
        displayNames: MutableMap<String, String>,
    ) {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) return
        if (trimmed.lowercase() in EXCLUDED_OPTION_NAMES) return

        val key = GenreNormalizer.toKey(trimmed)
        if (key.isBlank()) return

        val binding = SourceGenreBinding(
            sourceId = source.id,
            sourceName = source.name,
            applier = applier,
        )
        genreMap.getOrPut(key) { mutableListOf() }.add(binding)
        if (!displayNames.containsKey(key)) {
            displayNames[key] = GenreNormalizer.toDisplayName(trimmed, key)
        }
    }
}
