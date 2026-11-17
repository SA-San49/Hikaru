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
import eu.kanade.tachiyomi.ui.home.genre.SourceGenreBinding
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
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
import tachiyomi.source.localanime.LocalAnimeSource
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
    private val sourceFeedJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val genreJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val lastFeedSavedSearches = ConcurrentHashMap<Long, List<FeedSavedSearch>>()

    // In-memory cache for genre rows with short TTL to prevent repeated network requests on tab switch
    private val genreCache = ConcurrentHashMap<String, Pair<Long, List<Anime>>>()
    private val genreDiscoveryCache = ConcurrentHashMap<String, List<DiscoveredGenre>>()
    private val loadedSourceFeedKeys = ConcurrentHashMap<Long, List<String>>()
    private val sourceRequestLimiter = Semaphore(3)
    private val genreRowLimiter = Semaphore(3)

    companion object {
        private const val GENRE_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SOURCE_TIMEOUT_MS = 15000L
    }

    init {
        // Keep state synchronized with preference changes (without duplicate job triggers)
        uiPreferences.feedDisplayMode().changes()
            .distinctUntilChanged()
            .onEach { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
            .launchIn(screenModelScope)

        uiPreferences.feedGenreSelectionMode().changes()
            .distinctUntilChanged()
            .onEach { selectionMode ->
                mutableState.update { it.copy(genreSelectionMode = selectionMode) }
            }
            .launchIn(screenModelScope)

        uiPreferences.feedSelectedGenres().changes()
            .distinctUntilChanged()
            .onEach { selectedSet ->
                mutableState.update { it.copy(selectedGenres = selectedSet.toImmutableSet()) }
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
                        categoriesToUse.forEach { category ->
                            if (!newItems.containsKey(category.id)) {
                                newItems[category.id] = persistentListOf()
                            }
                        }
                        state.copy(
                            categories = categoriesToUse,
                            items = newItems.toImmutableMap(),
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
        if (state.value.displayMode == mode &&
            (mode != FeedDisplayMode.GENRES || state.value.discoveredGenres.isNotEmpty())
        ) {
            return
        }
        uiPreferences.feedDisplayMode().set(mode)
        mutableState.update { it.copy(displayMode = mode) }
        if (mode == FeedDisplayMode.GENRES) {
            triggerGenreReloadForAllCategories(forceDiscovery = false)
        } else {
            loadSourceFeedsIfNeeded()
        }
    }

    private fun loadSourceFeedsIfNeeded() {
        screenModelScope.launchIO {
            if (!sourceManager.isInitialized.value) {
                sourceManager.isInitialized.first { it }
            }
            state.value.categories.forEach { category ->
                val feeds = lastFeedSavedSearches[category.id]
                    ?: getFeedSavedSearchGlobal.await(category.id).also {
                        lastFeedSavedSearches[category.id] = it
                    }
                val structuralKey = feeds.map { "${it.id}:${it.type}:${it.savedSearch}" }
                if (loadedSourceFeedKeys[category.id] != structuralKey || state.value.items[category.id] == null) {
                    launchSourceFeedFetch(category, feeds, structuralKey)
                }
            }
        }
    }

    private fun launchSourceFeedFetch(
        category: FeedSavedSearchCategory,
        feeds: List<FeedSavedSearch>,
        structuralKey: List<String> = feeds.map { "${it.id}:${it.type}:${it.savedSearch}" },
    ) {
        loadedSourceFeedKeys[category.id] = structuralKey
        sourceFeedJobs[category.id]?.cancel()
        sourceFeedJobs[category.id] = screenModelScope.launchIO {
            fetchSourceFeedsForCategory(category, feeds)
        }
    }

    fun setGenreSelectionMode(mode: FeedGenreSelectionMode) {
        if (state.value.genreSelectionMode == mode) return
        uiPreferences.feedGenreSelectionMode().set(mode)
        mutableState.update { it.copy(genreSelectionMode = mode) }
        triggerGenreReloadForAllCategories(forceDiscovery = false)
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
        triggerGenreReloadForAllCategories(forceDiscovery = false)
    }

    fun selectAllGenres(categoryId: Long) {
        val genres = state.value.discoveredGenres[categoryId] ?: emptyList()
        val allKeys = genres.map { it.key }.toSet()
        uiPreferences.feedSelectedGenres().set(allKeys)
        mutableState.update { it.copy(selectedGenres = allKeys.toImmutableSet()) }
        triggerGenreReloadForAllCategories(forceDiscovery = false)
    }

    fun getBindingsForGenre(genreKey: String): List<SourceGenreBinding> {
        return state.value.discoveredGenres.values
            .flatten()
            .find { it.key == genreKey }
            ?.sourceBindings
            ?: emptyList()
    }

    fun deselectAllGenres() {
        uiPreferences.feedSelectedGenres().set(emptySet())
        mutableState.update { it.copy(selectedGenres = persistentSetOf()) }
        triggerGenreReloadForAllCategories(forceDiscovery = false)
    }

    fun fetchGenres(categoryId: Long? = null) {
        if (categoryId != null) {
            genreDiscoveryCache.keys.filter { it.startsWith("${categoryId}:") }.forEach {
                genreDiscoveryCache.remove(it)
            }
            val cat = state.value.categories.find { it.id == categoryId }
            if (cat != null) {
                val feeds = lastFeedSavedSearches[cat.id]
                loadGenresForCategory(cat, feeds, forceRefresh = false, forceDiscovery = true)
            }
        } else {
            genreDiscoveryCache.clear()
            triggerGenreReloadForAllCategories(forceRefresh = false, forceDiscovery = true)
        }
    }

    fun refresh(categoryId: Long? = null) {
        if (state.value.displayMode == FeedDisplayMode.SOURCES) {
            screenModelScope.launchIO {
                val targetCategories = if (categoryId != null) {
                    state.value.categories.filter { it.id == categoryId }
                } else {
                    state.value.categories
                }
                targetCategories.forEach { category ->
                    loadedSourceFeedKeys.remove(category.id)
                    val feeds = lastFeedSavedSearches[category.id] ?: getFeedSavedSearchGlobal.await(category.id)
                    launchSourceFeedFetch(category, feeds)
                }
            }
        } else {
            genreCache.clear()
            genreDiscoveryCache.clear()
            if (categoryId != null) {
                val cat = state.value.categories.find { it.id == categoryId }
                if (cat != null) {
                    val feeds = lastFeedSavedSearches[cat.id]
                    loadGenresForCategory(cat, feeds, forceRefresh = true, forceDiscovery = true)
                }
            } else {
                triggerGenreReloadForAllCategories(forceRefresh = true, forceDiscovery = true)
            }
        }
    }

    private fun triggerGenreReloadForAllCategories(
        forceRefresh: Boolean = false,
        forceDiscovery: Boolean = false,
    ) {
        state.value.categories.forEach { category ->
            val feeds = lastFeedSavedSearches[category.id]
            loadGenresForCategory(
                category = category,
                feedSavedSearches = feeds,
                forceRefresh = forceRefresh,
                forceDiscovery = forceDiscovery,
            )
        }
    }

    private fun setupFeedSubscriptions(categories: List<FeedSavedSearchCategory>) {
        val categoryIds = categories.map { it.id }.toSet()
        val removedIds = feedJobs.keys - categoryIds
        removedIds.forEach { id ->
            feedJobs[id]?.cancel()
            feedJobs.remove(id)
            sourceFeedJobs[id]?.cancel()
            sourceFeedJobs.remove(id)
            genreJobs[id]?.cancel()
            genreJobs.remove(id)
            lastFeedSavedSearches.remove(id)
            loadedSourceFeedKeys.remove(id)
            genreDiscoveryCache.keys.filter { it.startsWith("${id}:") }.forEach {
                genreDiscoveryCache.remove(it)
            }
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

                        val structuralKey = feedSavedSearches.map { "${it.id}:${it.type}:${it.savedSearch}" }
                        val feedsChanged = lastFeedSavedSearches[category.id]?.map { it.source } !=
                            feedSavedSearches.map { it.source }
                        val structureChanged = loadedSourceFeedKeys[category.id] != structuralKey
                        lastFeedSavedSearches[category.id] = feedSavedSearches

                        if (feedsChanged) {
                            loadedSourceFeedKeys.remove(category.id)
                            genreDiscoveryCache.keys.filter { it.startsWith("${category.id}:") }.forEach {
                                genreDiscoveryCache.remove(it)
                            }
                        }

                        // Auto-fetch live genres when in GENRES mode or when feed sources change
                        if (state.value.displayMode == FeedDisplayMode.GENRES || feedsChanged) {
                            loadGenresForCategory(
                                category = category,
                                feedSavedSearches = feedSavedSearches,
                                forceRefresh = false,
                                forceDiscovery = feedsChanged,
                            )
                        }

                        // Defer SOURCES network + DB fetches until SOURCES mode is active
                        if (state.value.displayMode == FeedDisplayMode.SOURCES) {
                            if (structureChanged || state.value.items[category.id] == null) {
                                launchSourceFeedFetch(category, feedSavedSearches, structuralKey)
                            }
                        } else if (feedsChanged || state.value.items[category.id] == null) {
                            val savedSearches = getSavedSearchGlobalFeed.await()
                            val placeholderItems = feedSavedSearches.mapNotNull { feed ->
                                val source = sourceManager.get(feed.source) as? AnimeCatalogueSource
                                    ?: return@mapNotNull null
                                val savedSearch = savedSearches.find { it.id == feed.savedSearch }
                                FeedItem(
                                    feed = feed,
                                    source = source,
                                    savedSearch = savedSearch,
                                    animeList = persistentListOf(),
                                    isLoading = false,
                                )
                            }.toImmutableList()
                            mutableState.update { state ->
                                val newItemsMap = state.items.toMutableMap()
                                newItemsMap[category.id] = placeholderItems
                                state.copy(items = newItemsMap.toImmutableMap())
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchSourceFeedsForCategory(
        category: FeedSavedSearchCategory,
        feedSavedSearches: List<FeedSavedSearch>,
    ) {
        val savedSearches = getSavedSearchGlobalFeed.await()

        val initialItems = feedSavedSearches.mapNotNull { feed ->
            val source = sourceManager.get(feed.source) as? AnimeCatalogueSource
                ?: return@mapNotNull null
            val savedSearch = savedSearches.find { it.id == feed.savedSearch }
            FeedItem(
                feed = feed,
                source = source,
                savedSearch = savedSearch,
                animeList = persistentListOf(),
                isLoading = true,
            )
        }.toImmutableList()

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
                        withTimeout(SOURCE_TIMEOUT_MS) {
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
                            // Resolve DB writes sequentially to avoid SQLite "database is locked" contention
                            val animeList = results.mapNotNull {
                                val domainAnime = it.toDomainAnime(source.id)
                                networkToLocalAnime.await(domainAnime)
                            }.distinctBy { it.id }.toImmutableList()

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

    private fun loadGenresForCategory(
        category: FeedSavedSearchCategory,
        feedSavedSearches: List<FeedSavedSearch>?,
        forceRefresh: Boolean = false,
        forceDiscovery: Boolean = false,
    ) {
        genreJobs[category.id]?.cancel()
        genreJobs[category.id] = screenModelScope.launchIO {
            // Ensure source manager is ready before scanning source filters
            if (!sourceManager.isInitialized.value) {
                sourceManager.isInitialized.first { it }
            }

            // Always read live feeds from DB if not cached yet
            val categoryFeeds = feedSavedSearches ?: getFeedSavedSearchGlobal.await(category.id).also {
                lastFeedSavedSearches[category.id] = it
            }

            // Source pool priority:
            // 1. Sources added to this category's feed
            // 2. If category feed is empty, sources added across all feed categories
            // 3. If feed is completely empty across app, fall back to enabled catalogue sources (excluding Local)
            var feedSources = categoryFeeds
                .mapNotNull { sourceManager.get(it.source) as? AnimeCatalogueSource }
                .distinctBy { it.id }

            if (feedSources.isEmpty()) {
                val allCategoryFeeds = state.value.categories.flatMap { cat ->
                    lastFeedSavedSearches[cat.id] ?: getFeedSavedSearchGlobal.await(cat.id)
                }
                feedSources = allCategoryFeeds
                    .mapNotNull { sourceManager.get(it.source) as? AnimeCatalogueSource }
                    .distinctBy { it.id }
            }

            val sourcePool = if (feedSources.isNotEmpty()) {
                feedSources
            } else {
                sourceManager.getCatalogueSources()
                    .filterIsInstance<AnimeCatalogueSource>()
                    .filterNot { it.id == LocalAnimeSource.ID }
            }

            mutableState.update { state ->
                val countMap = state.feedSourceCount.toMutableMap()
                countMap[category.id] = sourcePool.size
                state.copy(
                    discoveringCategories = (state.discoveringCategories + category.id).toImmutableSet(),
                    feedSourceCount = countMap.toImmutableMap(),
                )
            }

            val discovered: List<DiscoveredGenre> = try {
                if (sourcePool.isEmpty()) {
                    emptyList()
                } else {
                    val discoveryKey = "${category.id}:${sourcePool.map { it.id }.sorted().joinToString(",")}"
                    val cachedDiscovery = genreDiscoveryCache[discoveryKey]
                    if (!forceRefresh && !forceDiscovery && !cachedDiscovery.isNullOrEmpty()) {
                        cachedDiscovery
                    } else {
                        val found = GenreDiscoveryEngine.discoverGenresForSourcesAsync(sourcePool)
                        if (found.isNotEmpty()) {
                            genreDiscoveryCache[discoveryKey] = found
                        }
                        found
                    }
                }
            } finally {
                mutableState.update { state ->
                    state.copy(
                        discoveringCategories = (state.discoveringCategories - category.id).toImmutableSet(),
                    )
                }
            }

            val currentSelectionMode = uiPreferences.feedGenreSelectionMode().get()
            val currentSelectedKeys = uiPreferences.feedSelectedGenres().get()

            mutableState.update { state ->
                val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                newDiscoveredMap[category.id] = discovered.toImmutableList()
                state.copy(discoveredGenres = newDiscoveredMap.toImmutableMap())
            }

            // Skip fetching anime rows while we're in SOURCES mode (we only needed live genre discovery)
            if (state.value.displayMode != FeedDisplayMode.GENRES) {
                return@launchIO
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

            coroutineScope {
                genresToShow.forEach { genre ->
                    val cacheKey = "${category.id}_${genre.key}"
                    val cached = genreCache[cacheKey]
                    val isCacheValid = cached != null && (System.currentTimeMillis() - cached.first < GENRE_CACHE_TTL_MS) && !forceRefresh

                    if (!isCacheValid) {
                        launch {
                            genreRowLimiter.withPermit {
                                loadSingleGenreRow(category.id, genre)
                            }
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
                                withTimeout(SOURCE_TIMEOUT_MS) {
                                    val filters = source.getFilterList()
                                    val applied = binding.applier.apply(filters)
                                    if (!applied) return@withTimeout emptyList<Anime>()
                                    val searchResult = source.getSearchAnime(1, query = "", filters)
                                    searchResult.animes.mapNotNull { sAnime ->
                                        val domain = sAnime.toDomainAnime(source.id)
                                        networkToLocalAnime.await(domain)
                                    }
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
        val genreSelectionMode: FeedGenreSelectionMode = FeedGenreSelectionMode.SELECTED,
        val selectedGenres: ImmutableSet<String> = persistentSetOf(),
        val discoveredGenres: ImmutableMap<Long, ImmutableList<DiscoveredGenre>> = persistentMapOf(),
        val discoveringCategories: ImmutableSet<Long> = persistentSetOf(),
        val feedSourceCount: ImmutableMap<Long, Int> = persistentMapOf(),
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
