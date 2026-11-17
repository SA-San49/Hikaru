package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.browse.FilterSerializer
import eu.kanade.tachiyomi.ui.home.genre.DiscoveredGenre
import eu.kanade.tachiyomi.ui.home.genre.FeedDisplayMode
import eu.kanade.tachiyomi.ui.home.genre.FeedGenreSelectionMode
import eu.kanade.tachiyomi.ui.home.genre.GenreDiscoveryEngine
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedItem
import eu.kanade.tachiyomi.ui.home.genre.TitleDeduplicator
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toDomainAnime
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearchCategory
import tachiyomi.domain.source.interactor.UpdateFeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearchCategory
import tachiyomi.domain.source.model.FeedSavedSearchUpdate
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class FeedScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getFeedSavedSearchCategories: GetFeedSavedSearchCategories = Injekt.get(),
    private val insertFeedSavedSearchCategory: InsertFeedSavedSearchCategory = Injekt.get(),
    private val updateFeedSavedSearch: UpdateFeedSavedSearch = Injekt.get(),
    private val logActivity: LogActivity = Injekt.get(),
    private val filterSerializer: FilterSerializer = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : StateScreenModel<FeedScreenModel.State>(
    State(
        displayMode = uiPreferences.feedDisplayMode().get(),
        genreSelectionMode = uiPreferences.feedGenreSelectionMode().get(),
        selectedGenres = uiPreferences.feedSelectedGenres().get().toImmutableSet(),
    ),
) {

    private val feedJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val genreJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val lastFeedSavedSearches = ConcurrentHashMap<Long, List<FeedSavedSearch>>()

    // In-memory cache for genre rows with short TTL to prevent repeated network requests on tab switch
    private val genreCache = ConcurrentHashMap<String, Pair<Long, List<Anime>>>()
    private val genreDiscoveryCache = ConcurrentHashMap<Long, List<DiscoveredGenre>>()
    private val sourceRequestLimiter = Semaphore(3)

    companion object {
        private const val GENRE_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SOURCE_TIMEOUT_MS = 15000L
    }

    init {
        // Observe display mode preference changes
        uiPreferences.feedDisplayMode().changes()
            .onEach { mode ->
                mutableState.update { it.copy(displayMode = mode) }
                if (mode == FeedDisplayMode.GENRES) {
                    triggerGenreReloadForAllCategories()
                }
            }
            .launchIn(screenModelScope)

        // Observe genre selection mode preference changes
        uiPreferences.feedGenreSelectionMode().changes()
            .onEach { selectionMode ->
                mutableState.update { it.copy(genreSelectionMode = selectionMode) }
                if (mutableState.value.displayMode == FeedDisplayMode.GENRES) {
                    triggerGenreReloadForAllCategories()
                }
            }
            .launchIn(screenModelScope)

        // Observe selected genres set preference changes
        uiPreferences.feedSelectedGenres().changes()
            .onEach { selectedSet ->
                mutableState.update { it.copy(selectedGenres = selectedSet.toImmutableSet()) }
                if (mutableState.value.displayMode == FeedDisplayMode.GENRES &&
                    mutableState.value.genreSelectionMode == FeedGenreSelectionMode.SELECTED
                ) {
                    triggerGenreReloadForAllCategories()
                }
            }
            .launchIn(screenModelScope)

        screenModelScope.launchIO {
            getFeedSavedSearchCategories.subscribe()
                .onEach { updatedCategories ->
                    val categoriesToUse = if (updatedCategories.isEmpty()) {
                        screenModelScope.launchIO {
                            insertFeedSavedSearchCategory.await("Global")
                        }
                        persistentListOf(FeedSavedSearchCategory(id = 1, name = "Global", order = 0))
                    } else {
                        updatedCategories.toImmutableList()
                    }

                    mutableState.update { state ->
                        val newItems = state.items.toMutableMap()
                        val newGenreItems = state.genreItems.toMutableMap()
                        categoriesToUse.forEach { category ->
                            if (!newItems.containsKey(category.id)) {
                                newItems[category.id] = persistentListOf()
                            }
                            if (!newGenreItems.containsKey(category.id)) {
                                newGenreItems[category.id] = persistentListOf()
                            }
                        }
                        state.copy(
                            categories = categoriesToUse,
                            items = newItems.toImmutableMap(),
                            genreItems = newGenreItems.toImmutableMap(),
                        )
                    }

                    if (updatedCategories.isNotEmpty()) {
                        setupFeedSubscriptions(updatedCategories)
                    }
                }
                .launchIn(screenModelScope)
        }
    }

    fun onAnimeClicked(anime: Anime, feedId: Long?) {
        screenModelScope.launchIO {
            logActivity.await(anime.source, ActivityLog.TYPE_OPEN, feedId = feedId, animeId = anime.id)
        }
    }

    fun setDisplayMode(mode: FeedDisplayMode) {
        uiPreferences.feedDisplayMode().set(mode)
        mutableState.update { it.copy(displayMode = mode) }
        if (mode == FeedDisplayMode.GENRES) {
            triggerGenreReloadForAllCategories()
        }
    }

    fun setGenreSelectionMode(mode: FeedGenreSelectionMode) {
        uiPreferences.feedGenreSelectionMode().set(mode)
        mutableState.update { it.copy(genreSelectionMode = mode) }
        triggerGenreReloadForAllCategories()
    }

    fun toggleGenreSelected(genreKey: String) {
        val current = uiPreferences.feedSelectedGenres().get().toMutableSet()
        if (current.contains(genreKey)) {
            current.remove(genreKey)
        } else {
            current.add(genreKey)
        }
        uiPreferences.feedSelectedGenres().set(current)
        mutableState.update { it.copy(selectedGenres = current.toImmutableSet()) }
        triggerGenreReloadForAllCategories()
    }

    fun selectAllGenres(categoryId: Long) {
        val genres = state.value.discoveredGenres[categoryId] ?: emptyList()
        val allKeys = genres.map { it.key }.toSet()
        uiPreferences.feedSelectedGenres().set(allKeys)
        mutableState.update { it.copy(selectedGenres = allKeys.toImmutableSet()) }
        triggerGenreReloadForAllCategories()
    }

    fun getBindingsForGenre(genreKey: String): List<eu.kanade.tachiyomi.ui.home.genre.SourceGenreBinding> {
        return state.value.discoveredGenres.values
            .flatten()
            .find { it.key == genreKey }
            ?.sourceBindings
            ?: emptyList()
    }

    fun deselectAllGenres() {
        uiPreferences.feedSelectedGenres().set(emptySet())
        mutableState.update { it.copy(selectedGenres = persistentSetOf()) }
        triggerGenreReloadForAllCategories()
    }

    fun refresh(categoryId: Long? = null) {
        if (state.value.displayMode == FeedDisplayMode.SOURCES) {
            setupFeedSubscriptions(state.value.categories)
        } else {
            genreCache.clear()
            genreDiscoveryCache.clear()
            if (categoryId != null) {
                val cat = state.value.categories.find { it.id == categoryId }
                if (cat != null) {
                    val feeds = lastFeedSavedSearches[cat.id] ?: emptyList()
                    loadGenresForCategory(cat, feeds, forceRefresh = true)
                }
            } else {
                triggerGenreReloadForAllCategories(forceRefresh = true)
            }
        }
    }

    private fun triggerGenreReloadForAllCategories(forceRefresh: Boolean = false) {
        state.value.categories.forEach { category ->
            val feeds = lastFeedSavedSearches[category.id] ?: emptyList()
            loadGenresForCategory(category, feeds, forceRefresh = forceRefresh)
        }
    }

    private fun setupFeedSubscriptions(categories: List<FeedSavedSearchCategory>) {
        val categoryIds = categories.map { it.id }.toSet()
        val removedIds = feedJobs.keys - categoryIds
        removedIds.forEach { id ->
            feedJobs[id]?.cancel()
            feedJobs.remove(id)
            genreJobs[id]?.cancel()
            genreJobs.remove(id)
            lastFeedSavedSearches.remove(id)
            genreDiscoveryCache.remove(id)
        }

        categories.forEach { category ->
            if (!feedJobs.containsKey(category.id)) {
                feedJobs[category.id] = screenModelScope.launchIO {
                    combine(
                        getFeedSavedSearchGlobal.subscribe(category.id),
                        sourceManager.isInitialized,
                    ) { feedSavedSearches, isInitialized ->
                        feedSavedSearches to isInitialized
                    }.collectLatest { (feedSavedSearches, isInitialized) ->
                        if (!isInitialized) return@collectLatest

                        lastFeedSavedSearches[category.id] = feedSavedSearches

                        // If currently in GENRES mode, trigger genre discovery & loading
                        if (state.value.displayMode == FeedDisplayMode.GENRES) {
                            loadGenresForCategory(category, feedSavedSearches)
                        }

                        // Also populate source feed mode items as standard behavior
                        val savedSearches = getSavedSearchGlobalFeed.await()

                        val initialItems = feedSavedSearches.map { feed ->
                            val source = sourceManager.get(feed.source) as? AnimeCatalogueSource
                            if (source == null) null else {
                                val savedSearch = savedSearches.find { it.id == feed.savedSearch }
                                FeedItem(
                                    feed = feed,
                                    source = source,
                                    savedSearch = savedSearch,
                                    animeList = persistentListOf(),
                                    isLoading = true,
                                )
                            }
                        }.filterNotNull().toImmutableList()

                        mutableState.update { state ->
                            val newItemsMap = state.items.toMutableMap()
                            newItemsMap[category.id] = initialItems
                            state.copy(items = newItemsMap.toImmutableMap())
                        }

                        coroutineScope {
                            feedSavedSearches.forEach { feed ->
                                launch {
                                    val source = sourceManager.get(feed.source) as? AnimeCatalogueSource
                                        ?: return@launch
                                    try {
                                        kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                                            val results = when (FeedSavedSearch.Type.from(feed.type)) {
                                                FeedSavedSearch.Type.Latest -> {
                                                    try {
                                                        source.getLatestUpdates(1).animes
                                                    } catch (e: Exception) {
                                                        source.getPopularAnime(1).animes
                                                    }
                                                }
                                                FeedSavedSearch.Type.Popular -> source.getPopularAnime(1).animes
                                                FeedSavedSearch.Type.SavedSearch -> {
                                                    val savedSearch = savedSearches.find { it.id == feed.savedSearch }
                                                    if (savedSearch != null) {
                                                        val filters = source.getFilterList()
                                                        savedSearch.filtersJson?.let {
                                                            filterSerializer.deserialize(filters, it)
                                                        }
                                                        try {
                                                            source.getSearchAnime(1, savedSearch.query ?: "", filters).animes
                                                        } catch (e: Exception) {
                                                            logcat(LogPriority.ERROR, e) { "Saved search failed for source: ${source.name} (ID: ${source.id})" }
                                                            emptyList()
                                                        }
                                                    } else {
                                                        emptyList()
                                                    }
                                                }
                                            }
                                            val animeList = results.map {
                                                async {
                                                    val domainAnime = it.toDomainAnime(source.id)
                                                    networkToLocalAnime.await(domainAnime)
                                                }
                                            }.awaitAll().filterNotNull().distinctBy { it.id }.toImmutableList()

                                            val currentTopUrl = results.firstOrNull()?.url
                                            val previousTopUrl = feed.lastTopUrl
                                            if (currentTopUrl != null && currentTopUrl != previousTopUrl) {
                                                val newItemsCount = if (previousTopUrl == null) {
                                                    results.size
                                                } else {
                                                    val index = results.indexOfFirst { it.url == previousTopUrl }
                                                    if (index == -1) results.size else index
                                                }

                                                if (newItemsCount > 0) {
                                                    logActivity.await(source.id, ActivityLog.TYPE_FEED_UPDATE, feedId = feed.id, count = newItemsCount.toLong())
                                                }
                                                updateFeedSavedSearch.await(
                                                    FeedSavedSearchUpdate(
                                                        id = feed.id,
                                                        lastTopUrl = currentTopUrl,
                                                    ),
                                                )
                                            }
                                            mutableState.update { state ->
                                                val currentCategoryItems = state.items[category.id] ?: initialItems
                                                val updatedItems = currentCategoryItems.map { item ->
                                                    if (item.feed.id == feed.id) {
                                                        item.copy(animeList = animeList, isLoading = false)
                                                    } else {
                                                        item
                                                    }
                                                }.toImmutableList()

                                                val newItemsMap = state.items.toMutableMap()
                                                newItemsMap[category.id] = updatedItems
                                                state.copy(items = newItemsMap.toImmutableMap())
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "Feed fetch failed or timed out for ${source.name}" }
                                        mutableState.update { state ->
                                            val currentCategoryItems = state.items[category.id] ?: initialItems
                                            val updatedItems = currentCategoryItems.map { item ->
                                                if (item.feed.id == feed.id) {
                                                    item.copy(isLoading = false)
                                                } else {
                                                    item
                                                }
                                            }.toImmutableList()

                                            val newItemsMap = state.items.toMutableMap()
                                            newItemsMap[category.id] = updatedItems
                                            state.copy(items = newItemsMap.toImmutableMap())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadGenresForCategory(
        category: FeedSavedSearchCategory,
        feedSavedSearches: List<FeedSavedSearch>,
        forceRefresh: Boolean = false,
    ) {
        genreJobs[category.id]?.cancel()
        genreJobs[category.id] = screenModelScope.launchIO {
            // Source pool = catalogue sources in user's current feed for active category (preferred),
            // or all enabled catalogue sources if feed list is empty.
            val feedSources = feedSavedSearches
                .mapNotNull { sourceManager.get(it.source) as? AnimeCatalogueSource }
                .distinctBy { it.id }

            val sourcePool = if (feedSources.isNotEmpty()) {
                feedSources
            } else {
                sourceManager.getCatalogueSources().filterIsInstance<AnimeCatalogueSource>()
            }

            if (sourcePool.isEmpty()) {
                mutableState.update { state ->
                    val newMap = state.genreItems.toMutableMap()
                    newMap[category.id] = persistentListOf()
                    state.copy(genreItems = newMap.toImmutableMap())
                }
                return@launchIO
            }

            // Genre discovery with cache
            val discovered = if (!forceRefresh && genreDiscoveryCache.containsKey(category.id)) {
                genreDiscoveryCache[category.id]!!
            } else {
                val found = GenreDiscoveryEngine.discoverGenresForSources(sourcePool)
                genreDiscoveryCache[category.id] = found
                found
            }

            val currentSelectionMode = uiPreferences.feedGenreSelectionMode().get()
            val currentSelectedKeys = uiPreferences.feedSelectedGenres().get()

            mutableState.update { state ->
                val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                newDiscoveredMap[category.id] = discovered.toImmutableList()
                state.copy(discoveredGenres = newDiscoveredMap.toImmutableMap())
            }

            val genresToShow = when (currentSelectionMode) {
                FeedGenreSelectionMode.ALL -> discovered
                FeedGenreSelectionMode.SELECTED -> discovered.filter { it.key in currentSelectedKeys }
            }

            if (genresToShow.isEmpty()) {
                mutableState.update { state ->
                    val newMap = state.genreItems.toMutableMap()
                    newMap[category.id] = persistentListOf()
                    state.copy(genreItems = newMap.toImmutableMap())
                }
                return@launchIO
            }

            // Setup initial rows in state
            val initialGenreItems = genresToShow.map { genre ->
                val cacheKey = "${category.id}_${genre.key}"
                val cached = genreCache[cacheKey]
                val isCacheValid = cached != null && (System.currentTimeMillis() - cached.first < GENRE_CACHE_TTL_MS) && !forceRefresh

                if (isCacheValid && cached != null) {
                    GenreFeedItem(
                        key = genre.key,
                        title = genre.displayName,
                        sourceCount = genre.sourceBindings.size,
                        animeList = cached.second.toImmutableList(),
                        isLoading = false,
                    )
                } else {
                    GenreFeedItem(
                        key = genre.key,
                        title = genre.displayName,
                        sourceCount = genre.sourceBindings.size,
                        animeList = persistentListOf(),
                        isLoading = true,
                    )
                }
            }.toImmutableList()

            mutableState.update { state ->
                val newMap = state.genreItems.toMutableMap()
                newMap[category.id] = initialGenreItems
                state.copy(genreItems = newMap.toImmutableMap())
            }

            // Load uncached rows in parallel with concurrency cap
            coroutineScope {
                genresToShow.forEach { genre ->
                    val cacheKey = "${category.id}_${genre.key}"
                    val cached = genreCache[cacheKey]
                    val isCacheValid = cached != null && (System.currentTimeMillis() - cached.first < GENRE_CACHE_TTL_MS) && !forceRefresh

                    if (!isCacheValid) {
                        launch {
                            loadSingleGenreRow(category.id, genre)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadSingleGenreRow(categoryId: Long, genre: DiscoveredGenre) {
        val animeFromSources = mutableListOf<Anime>()
        try {
            coroutineScope {
                val sourceResults = genre.sourceBindings.map { binding ->
                    async {
                        sourceRequestLimiter.withPermit {
                            val source = sourceManager.get(binding.sourceId) as? AnimeCatalogueSource
                                ?: return@withPermit emptyList<Anime>()
                            try {
                                kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                                    val filters = source.getFilterList()
                                    val applied = binding.applier.apply(filters)
                                    if (!applied) return@withTimeout emptyList<Anime>()
                                    val searchResult = source.getSearchAnime(1, query = "", filters)
                                    val animes = searchResult.animes.map { sAnime ->
                                        async {
                                            val domain = sAnime.toDomainAnime(source.id)
                                            networkToLocalAnime.await(domain)
                                        }
                                    }.awaitAll().filterNotNull()
                                    animes
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) {
                                    "Genre search failed or timed out for source ${source.name} in genre ${genre.displayName}"
                                }
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()

                for (list in sourceResults) {
                    animeFromSources.addAll(list)
                }
            }

            // Deduplicate anime within this genre row by normalized title
            val dedupedAnime = TitleDeduplicator.dedupeAnime(animeFromSources).toImmutableList()

            val cacheKey = "${categoryId}_${genre.key}"
            genreCache[cacheKey] = Pair(System.currentTimeMillis(), dedupedAnime)

            mutableState.update { state ->
                val currentCategoryItems = state.genreItems[categoryId] ?: return@update state
                val updatedItems = currentCategoryItems.map { item ->
                    if (item.key == genre.key) {
                        item.copy(
                            animeList = dedupedAnime,
                            isLoading = false,
                        )
                    } else {
                        item
                    }
                }.toImmutableList()

                val newMap = state.genreItems.toMutableMap()
                newMap[categoryId] = updatedItems
                state.copy(genreItems = newMap.toImmutableMap())
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load genre row for ${genre.displayName}" }
            mutableState.update { state ->
                val currentCategoryItems = state.genreItems[categoryId] ?: return@update state
                val updatedItems = currentCategoryItems.map { item ->
                    if (item.key == genre.key) {
                        item.copy(isLoading = false, errorMessage = "Failed to load")
                    } else {
                        item
                    }
                }.toImmutableList()

                val newMap = state.genreItems.toMutableMap()
                newMap[categoryId] = updatedItems
                state.copy(genreItems = newMap.toImmutableMap())
            }
        }
    }

    @Immutable
    data class State(
        val items: ImmutableMap<Long, ImmutableList<FeedItem>> = persistentMapOf(),
        val genreItems: ImmutableMap<Long, ImmutableList<GenreFeedItem>> = persistentMapOf(),
        val categories: ImmutableList<FeedSavedSearchCategory> = persistentListOf(),
        val displayMode: FeedDisplayMode = FeedDisplayMode.SOURCES,
        val genreSelectionMode: FeedGenreSelectionMode = FeedGenreSelectionMode.ALL,
        val selectedGenres: ImmutableSet<String> = persistentSetOf(),
        val discoveredGenres: ImmutableMap<Long, ImmutableList<DiscoveredGenre>> = persistentMapOf(),
    ) {
        val isLoading: Boolean
            get() = if (displayMode == FeedDisplayMode.SOURCES) {
                items.isEmpty() && categories.isNotEmpty()
            } else {
                genreItems.isEmpty() && categories.isNotEmpty()
            }
    }

    @Immutable
    data class FeedItem(
        val feed: FeedSavedSearch,
        val source: AnimeCatalogueSource,
        val savedSearch: SavedSearch?,
        val animeList: ImmutableList<Anime>,
        val isLoading: Boolean = false,
    )
}
