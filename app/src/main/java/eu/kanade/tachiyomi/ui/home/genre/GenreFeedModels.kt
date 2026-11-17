package eu.kanade.tachiyomi.ui.home.genre

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.anime.model.Anime

enum class FeedDisplayMode {
    SOURCES,
    GENRES,
}

enum class FeedGenreSelectionMode {
    ALL,
    SELECTED,
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
