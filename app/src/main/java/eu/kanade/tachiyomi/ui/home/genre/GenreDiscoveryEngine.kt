package eu.kanade.tachiyomi.ui.home.genre

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
    )

    private val EXCLUDED_OPTION_NAMES = setOf(
        "all",
        "any",
        "none",
        "choose",
        "select",
        "default",
        "everything",
        "-",
        "--",
    )

    fun isGenreLikeGroupOrFilterName(name: String): Boolean {
        val lower = name.lowercase().trim()
        return GENRE_KEYWORDS.any { lower.contains(it) }
    }

    fun discoverGenresForSources(sources: List<AnimeCatalogueSource>): List<DiscoveredGenre> {
        val genreMap = mutableMapOf<String, MutableList<SourceGenreBinding>>()
        val displayNames = mutableMapOf<String, String>()

        for (source in sources) {
            val filterList = try {
                source.getFilterList()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to get filter list from ${source.name}" }
                continue
            }

            for (filter in filterList) {
                when (filter) {
                    is AnimeFilter.Group<*> -> {
                        if (isGenreLikeGroupOrFilterName(filter.name)) {
                            for (item in filter.state) {
                                when (item) {
                                    is AnimeFilter.CheckBox -> {
                                        registerOption(
                                            source = source,
                                            rawName = item.name,
                                            applier = GenreFilterApplier.GroupCheckBox(filter.name, item.name),
                                            genreMap = genreMap,
                                            displayNames = displayNames,
                                        )
                                    }
                                    is AnimeFilter.TriState -> {
                                        registerOption(
                                            source = source,
                                            rawName = item.name,
                                            applier = GenreFilterApplier.GroupTriState(filter.name, item.name),
                                            genreMap = genreMap,
                                            displayNames = displayNames,
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                    is AnimeFilter.Select<*> -> {
                        if (isGenreLikeGroupOrFilterName(filter.name)) {
                            filter.values.forEachIndexed { index, option ->
                                val rawName = option?.toString() ?: return@forEachIndexed
                                val lower = rawName.lowercase().trim()
                                if (lower in EXCLUDED_OPTION_NAMES || lower.startsWith("all ")) {
                                    return@forEachIndexed
                                }
                                registerOption(
                                    source = source,
                                    rawName = rawName,
                                    applier = GenreFilterApplier.SelectOption(filter.name, index, rawName),
                                    genreMap = genreMap,
                                    displayNames = displayNames,
                                )
                            }
                        }
                    }
                    is AnimeFilter.CheckBox -> {
                        if (isGenreLikeGroupOrFilterName(filter.name)) {
                            registerOption(
                                source = source,
                                rawName = filter.name,
                                applier = GenreFilterApplier.TopLevelCheckBox(filter.name),
                                genreMap = genreMap,
                                displayNames = displayNames,
                            )
                        }
                    }
                    is AnimeFilter.TriState -> {
                        if (isGenreLikeGroupOrFilterName(filter.name)) {
                            registerOption(
                                source = source,
                                rawName = filter.name,
                                applier = GenreFilterApplier.TopLevelTriState(filter.name),
                                genreMap = genreMap,
                                displayNames = displayNames,
                            )
                        }
                    }
                    else -> {}
                }
            }
        }

        return genreMap.map { (key, bindings) ->
            DiscoveredGenre(
                key = key,
                displayName = displayNames[key] ?: GenreNormalizer.toDisplayName(key),
                sourceBindings = bindings.distinctBy { it.sourceId },
            )
        }.sortedBy { it.displayName }
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
