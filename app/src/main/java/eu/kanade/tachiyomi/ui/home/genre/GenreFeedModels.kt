package eu.kanade.tachiyomi.ui.home.genre

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime

enum class FeedDisplayMode {
    SOURCES,
    GENRES,
}

enum class FeedGenreSelectionMode {
    ALL,
    SELECTED,
}

object GenreNormalizer {
    private val ALIASES = mapOf(
        "actions" to "action",
        "adventures" to "adventure",
        "comedies" to "comedy",
        "dramas" to "drama",
        "fantasies" to "fantasy",
        "horrors" to "horror",
        "mysteries" to "mystery",
        "romances" to "romance",
        "sci-fi" to "sci-fi",
        "scifi" to "sci-fi",
        "science fiction" to "sci-fi",
        "slice of life" to "slice of life",
        "supernaturals" to "supernatural",
        "thrillers" to "thriller",
        "psychologicals" to "psychological",
        "martial arts" to "martial arts",
        "historical" to "historical",
        "historicals" to "historical",
        "shounen" to "shounen",
        "shonen" to "shounen",
        "shoujo" to "shoujo",
        "shojo" to "shoujo",
        "seinen" to "seinen",
        "josei" to "josei",
        "isekai" to "isekai",
        "mecha" to "mecha",
        "sports" to "sports",
        "sport" to "sports",
    )

    fun toKey(rawName: String): String {
        val collapsed = rawName.trim().replace(Regex("\\s+"), " ").lowercase()
        return ALIASES[collapsed] ?: collapsed
    }

    fun toDisplayName(rawName: String, key: String = toKey(rawName)): String {
        return when (key) {
            "sci-fi" -> "Sci-Fi"
            "slice of life" -> "Slice of Life"
            "martial arts" -> "Martial Arts"
            "tv" -> "TV"
            "ova" -> "OVA"
            "ona" -> "ONA"
            else -> {
                key.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            }
        }
    }
}

object TitleDeduplicator {
    private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
    private val SPACES_REGEX = Regex("\\s+")

    fun normalizeTitle(title: String): String {
        return title
            .trim()
            .lowercase()
            .replace(PUNCTUATION_REGEX, "")
            .replace(SPACES_REGEX, " ")
            .trim()
    }

    fun dedupeAnime(items: List<Anime>): List<Anime> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Anime>()
        for (item in items) {
            val key = normalizeTitle(item.title)
            val effectiveKey = if (key.isBlank()) item.title.trim().lowercase() else key
            if (seen.add(effectiveKey)) {
                result.add(item)
            }
        }
        return result
    }
}

sealed interface GenreFilterApplier {
    fun apply(filters: AnimeFilterList): Boolean

    data class GroupCheckBox(val groupName: String, val itemName: String) : GenreFilterApplier {
        override fun apply(filters: AnimeFilterList): Boolean {
            val group = filters.filterIsInstance<AnimeFilter.Group<*>>().find {
                it.name.equals(groupName, ignoreCase = true)
            } ?: return false
            val item = group.state.filterIsInstance<AnimeFilter.CheckBox>().find {
                it.name.equals(itemName, ignoreCase = true)
            } ?: return false
            item.state = true
            return true
        }
    }

    data class GroupTriState(val groupName: String, val itemName: String) : GenreFilterApplier {
        override fun apply(filters: AnimeFilterList): Boolean {
            val group = filters.filterIsInstance<AnimeFilter.Group<*>>().find {
                it.name.equals(groupName, ignoreCase = true)
            } ?: return false
            val item = group.state.filterIsInstance<AnimeFilter.TriState>().find {
                it.name.equals(itemName, ignoreCase = true)
            } ?: return false
            item.state = AnimeFilter.TriState.STATE_INCLUDE
            return true
        }
    }

    data class SelectOption(val selectName: String, val optionIndex: Int, val optionName: String) : GenreFilterApplier {
        override fun apply(filters: AnimeFilterList): Boolean {
            val select = filters.filterIsInstance<AnimeFilter.Select<*>>().find {
                it.name.equals(selectName, ignoreCase = true)
            } ?: return false
            val targetIndex = if (select.values.getOrNull(optionIndex)?.toString().equals(optionName, ignoreCase = true)) {
                optionIndex
            } else {
                select.values.indexOfFirst { it?.toString().equals(optionName, ignoreCase = true) }
            }
            if (targetIndex >= 0) {
                select.state = targetIndex
                return true
            }
            return false
        }
    }

    data class TopLevelCheckBox(val filterName: String) : GenreFilterApplier {
        override fun apply(filters: AnimeFilterList): Boolean {
            val filter = filters.filterIsInstance<AnimeFilter.CheckBox>().find {
                it.name.equals(filterName, ignoreCase = true)
            } ?: return false
            filter.state = true
            return true
        }
    }

    data class TopLevelTriState(val filterName: String) : GenreFilterApplier {
        override fun apply(filters: AnimeFilterList): Boolean {
            val filter = filters.filterIsInstance<AnimeFilter.TriState>().find {
                it.name.equals(filterName, ignoreCase = true)
            } ?: return false
            filter.state = AnimeFilter.TriState.STATE_INCLUDE
            return true
        }
    }
}

data class SourceGenreBinding(
    val sourceId: Long,
    val sourceName: String,
    val applier: GenreFilterApplier,
)

data class DiscoveredGenre(
    val key: String,
    val displayName: String,
    val sourceBindings: List<SourceGenreBinding>,
)

@Immutable
data class GenreFeedItem(
    val key: String,
    val title: String,
    val sourceCount: Int,
    val animeList: ImmutableList<Anime> = persistentListOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

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

    private fun isGenreLikeGroupOrFilterName(name: String): Boolean {
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
                                if (lower in listOf("all", "any", "none", "choose", "select", "default", "", "-") || lower.startsWith("all ")) {
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
