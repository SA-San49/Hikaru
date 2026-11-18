package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.browse.FilterSerializer
import eu.kanade.tachiyomi.ui.home.genre.AnimeRecencyResolver
import eu.kanade.tachiyomi.ui.home.genre.DiscoveredGenre
import eu.kanade.tachiyomi.ui.home.genre.FeedDisplayMode
import eu.kanade.tachiyomi.ui.home.genre.FeedGenreSelectionMode
import eu.kanade.tachiyomi.ui.home.genre.FeedSourceOption
import eu.kanade.tachiyomi.ui.home.genre.GenreDiscoveryEngine
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedItem
import eu.kanade.tachiyomi.ui.home.genre.GenreNormalizer
import eu.kanade.tachiyomi.ui.home.genre.LatestGenreEngine
import eu.kanade.tachiyomi.ui.home.genre.LatestGenreSnapshot
import eu.kanade.tachiyomi.ui.home.genre.RankedAnime
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
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
        prioritySourceIds = uiPreferences.feedPrioritySourceIds().get().toImmutableList(),
    ),
) {

    private val feedJobs = ConcurrentHashMap<Long, Job>()
    private val sourceFeedJobs = ConcurrentHashMap<Long, Job>()
    private val genreJobs = ConcurrentHashMap<Long, Job>()
    private val lastFeedSavedSearches = ConcurrentHashMap<Long, List<FeedSavedSearch>>()

    // In-memory cache for genre rows with short TTL to prevent repeated network requests on tab switch
    private val genreCache = ConcurrentHashMap<String, Pair<Long, List<Anime>>>()
    private val genreDiscoveryCache = ConcurrentHashMap<String, List<DiscoveredGenre>>()
    private val latestSnapshotCache = ConcurrentHashMap<Long, LatestGenreSnapshot>()
    private val latestMatchesByCategory = ConcurrentHashMap<Long, Map<String, List<Anime>>>()
    private val tapResolutionCache = ConcurrentHashMap<String, Anime>()
    private val loadedSourceFeedKeys = ConcurrentHashMap<Long, List<String>>()
    private val sourceRequestLimiter = Semaphore(3)
    private val genreRowLimiter = Semaphore(3)
    private val dbWriteMutex = Mutex()
    private val recencyResolver = AnimeRecencyResolver()
    private val latestGenreEngine = LatestGenreEngine()
    private var tapResolutionJob: Job? = null

    companion object {
        private const val GENRE_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SOURCE_TIMEOUT_MS = 15000L
        private const val TAP_RESOLVE_PRIORITY_TIMEOUT_MS = 2200L
        private const val TAP_RESOLVE_RANDOM_TIMEOUT_MS = 1500L
        private const val MAX_RANDOM_FALLBACK_SOURCES = 2
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

        uiPreferences.feedPrioritySourceIds().changes()
            .distinctUntilChanged()
            .onEach { priorityIds ->
                tapResolutionCache.clear()
                mutableState.update { it.copy(prioritySourceIds = priorityIds.toImmutableList()) }
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
            (mode != FeedDisplayMode.GENRES || state.value.genreItems.isNotEmpty())
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
        applyGenreFilterChangeFast()
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
        applyGenreFilterChangeFast()
    }

    fun selectAllGenres(categoryId: Long) {
        val genres = state.value.discoveredGenres[categoryId] ?: emptyList()
        val allKeys = genres.map { it.key }.toSet()
        uiPreferences.feedSelectedGenres().set(allKeys)
        mutableState.update { it.copy(selectedGenres = allKeys.toImmutableSet()) }
        applyGenreFilterChangeFast()
    }

    fun getBindingsForGenre(genreKey: String, categoryId: Long? = null): List<SourceGenreBinding> {
        val discoveredList = if (categoryId != null) {
            state.value.discoveredGenres[categoryId].orEmpty()
        } else {
            state.value.discoveredGenres.values.flatten()
        }
        return discoveredList.find { it.key == genreKey }?.sourceBindings ?: emptyList()
    }

    fun getPreferredSourceBindingForGenre(genreKey: String, categoryId: Long? = null): SourceGenreBinding? {
        val bindings = getBindingsForGenre(genreKey, categoryId)
        if (bindings.isEmpty()) return null
        val priorities = state.value.prioritySourceIds
        for (priorityId in priorities) {
            bindings.firstOrNull { it.sourceId == priorityId }?.let { return it }
        }
        return bindings.randomOrNull()
    }

    fun getPreferredSourceForGenreSeeAll(
        genreKey: String,
        item: GenreFeedItem,
        categoryId: Long? = null,
    ): Long? {
        val bindings = getBindingsForGenre(genreKey, categoryId)
        val bindingSourceIds = bindings.map { it.sourceId }.distinct()
        val itemSourceIds = item.animeList.map { it.source }.distinct()
        val feedPoolIds = resolveAvailableFeedSourceIds(categoryId)
        val candidatePool = when {
            bindingSourceIds.isNotEmpty() -> bindingSourceIds
            itemSourceIds.isNotEmpty() -> itemSourceIds
            else -> feedPoolIds
        }
        if (candidatePool.isEmpty()) return null

        for (priorityId in state.value.prioritySourceIds) {
            if (priorityId in candidatePool) return priorityId
        }
        return candidatePool.randomOrNull()
    }

    fun togglePrioritySource(sourceId: Long) {
        val current = uiPreferences.feedPrioritySourceIds().get().toMutableList()
        if (sourceId in current) {
            current.remove(sourceId)
        } else if (current.size < 3) {
            current.add(sourceId)
        } else {
            current[2] = sourceId
        }
        val finalized = current.distinct().take(3)
        uiPreferences.feedPrioritySourceIds().set(finalized)
        tapResolutionCache.clear()
        mutableState.update { it.copy(prioritySourceIds = finalized.toImmutableList()) }
    }

    fun clearPrioritySources() {
        uiPreferences.feedPrioritySourceIds().set(emptyList())
        tapResolutionCache.clear()
        mutableState.update { it.copy(prioritySourceIds = persistentListOf()) }
    }

    private data class CandidateResolution(
        val sourceId: Long,
        val existingAnime: Anime? = null,
        val sAnime: eu.kanade.tachiyomi.animesource.model.SAnime? = null,
    )

    private suspend fun resolveCandidateRaw(
        candidateSourceId: Long,
        targetTitle: String,
        originalAnime: Anime,
        knownLatestMatches: List<Anime>,
    ): CandidateResolution? {
        if (candidateSourceId == originalAnime.source) {
            return CandidateResolution(sourceId = candidateSourceId, existingAnime = originalAnime)
        }
        knownLatestMatches.firstOrNull { it.source == candidateSourceId }?.let { preloaded ->
            return CandidateResolution(sourceId = candidateSourceId, existingAnime = preloaded)
        }
        val source = sourceManager.get(candidateSourceId) as? AnimeCatalogueSource ?: return null
        return withTimeoutOrNull(TAP_RESOLVE_PRIORITY_TIMEOUT_MS) {
            runCatching {
                val searchResult = source.getSearchAnime(
                    page = 1,
                    query = targetTitle,
                    filters = source.getFilterList(),
                )
                val match = TitleDeduplicator.selectBestMatch(
                    targetTitle = targetTitle,
                    candidates = searchResult.animes,
                    titleSelector = { it.title },
                ) ?: return@runCatching null
                CandidateResolution(sourceId = candidateSourceId, sAnime = match)
            }.getOrNull()
        }
    }

    fun resolveGenreAnimeClick(
        anime: Anime,
        categoryId: Long?,
        onResolved: (Anime) -> Unit,
    ) {
        val availableIds = resolveAvailableFeedSourceIds(categoryId)
        val activePriorities = state.value.prioritySourceIds
            .filter { it in availableIds }
            .distinct()
            .take(3)

        // Fast path 1: instant open if no priorities configured or Priority 1 already owns this card
        if (activePriorities.isEmpty() || activePriorities.first() == anime.source) {
            onResolved(anime)
            return
        }

        val normTitle = TitleDeduplicator.normalizeTitle(anime.title)
        // Fast path 2: instant open if Latest feed already collected this anime from Priority 1
        val knownLatestMatches = (categoryId?.let { latestMatchesByCategory[it]?.get(normTitle) }
            ?: latestMatchesByCategory.values.asSequence().mapNotNull { it[normTitle] }.firstOrNull())
            .orEmpty()
        knownLatestMatches.firstOrNull { it.source == activePriorities.first() }?.let { topLatest ->
            onResolved(topLatest)
            return
        }

        val cacheKey = "$normTitle|${activePriorities.joinToString(",")}|${anime.source}"
        tapResolutionCache[cacheKey]?.let { cached ->
            onResolved(cached)
            return
        }

        tapResolutionJob?.cancel()
        val firstPriorityName = sourceManager.get(activePriorities.first())?.name
        mutableState.update { it.copy(resolvingAnime = anime, resolvingSourceName = firstPriorityName) }

        tapResolutionJob = screenModelScope.launchIO {
            // Race active priorities (1st, 2nd, 3rd) in parallel while enforcing strict priority precedence
            val priorityWinner: CandidateResolution? = coroutineScope {
                val priorityJobs = activePriorities.map { prioritySourceId ->
                    async {
                        resolveCandidateRaw(
                            candidateSourceId = prioritySourceId,
                            targetTitle = anime.title,
                            originalAnime = anime,
                            knownLatestMatches = knownLatestMatches,
                        )
                    }
                }
                var selected: CandidateResolution? = null
                for (i in priorityJobs.indices) {
                    val result = priorityJobs[i].await()
                    if (result != null) {
                        selected = result
                        for (j in (i + 1) until priorityJobs.size) {
                            priorityJobs[j].cancel()
                        }
                        break
                    }
                }
                selected
            }

            // Only if top 3 priorities all missed AND card's original source wasn't in priorities,
            // briefly race up to 2 random remaining feed sources; otherwise fall back immediately to original card.
            val winningCandidate: CandidateResolution? = priorityWinner ?: run {
                val randomPool = availableIds
                    .filter { it !in activePriorities && it != anime.source }
                    .shuffled()
                    .take(MAX_RANDOM_FALLBACK_SOURCES)
                if (randomPool.isEmpty()) {
                    null
                } else {
                    withTimeoutOrNull(TAP_RESOLVE_RANDOM_TIMEOUT_MS) {
                        val winnerDeferred = CompletableDeferred<CandidateResolution?>()
                        coroutineScope {
                            val jobs = randomPool.map { sId ->
                                launch {
                                    val hit = resolveCandidateRaw(
                                        candidateSourceId = sId,
                                        targetTitle = anime.title,
                                        originalAnime = anime,
                                        knownLatestMatches = knownLatestMatches,
                                    )
                                    if (hit != null) {
                                        winnerDeferred.complete(hit)
                                    }
                                }
                            }
                            val watcher = launch {
                                jobs.forEach { it.join() }
                                winnerDeferred.complete(null)
                            }
                            val found = winnerDeferred.await()
                            watcher.cancel()
                            jobs.forEach { it.cancel() }
                            found
                        }
                    }
                }
            }

            val finalAnime = when {
                winningCandidate?.existingAnime != null -> winningCandidate.existingAnime
                winningCandidate?.sAnime != null -> dbWriteMutex.withLock {
                    networkToLocalAnime.await(winningCandidate.sAnime.toDomainAnime(winningCandidate.sourceId))
                }
                else -> anime
            }

            tapResolutionCache[cacheKey] = finalAnime
            mutableState.update { it.copy(resolvingAnime = null, resolvingSourceName = null) }
            withContext(Dispatchers.Main) {
                onResolved(finalAnime)
            }
        }
    }

    fun cancelTapResolution(onFallback: (Anime) -> Unit) {
        val fallback = state.value.resolvingAnime
        tapResolutionJob?.cancel()
        tapResolutionJob = null
        mutableState.update { it.copy(resolvingAnime = null, resolvingSourceName = null) }
        if (fallback != null) {
            onFallback(fallback)
        }
    }

    private fun resolveAvailableFeedSourceIds(categoryId: Long?): List<Long> {
        val fromCategory = categoryId?.let { state.value.feedSourcesByCategory[it] }
            ?.map { it.sourceId }
            .orEmpty()
        if (fromCategory.isNotEmpty()) return fromCategory

        val fromAllCategories = state.value.feedSourcesByCategory.values
            .flatten()
            .map { it.sourceId }
            .distinct()
        if (fromAllCategories.isNotEmpty()) return fromAllCategories

        return sourceManager.getCatalogueSources()
            .filterIsInstance<AnimeCatalogueSource>()
            .filterNot { it.id == LocalAnimeSource.ID }
            .map { it.id }
    }

    fun deselectAllGenres() {
        uiPreferences.feedSelectedGenres().set(emptySet())
        mutableState.update { it.copy(selectedGenres = persistentSetOf()) }
        applyGenreFilterChangeFast()
    }

    private fun applyGenreFilterChangeFast() {
        state.value.categories.forEach { category ->
            val snapshot = latestSnapshotCache[category.id]
            if (snapshot != null) {
                rebuildGenreItemsFromSnapshot(
                    categoryId = category.id,
                    snapshot = snapshot,
                    isStillLoading = false,
                )
            } else if (state.value.displayMode == FeedDisplayMode.GENRES) {
                val feeds = lastFeedSavedSearches[category.id]
                loadGenresForCategory(category, feeds, forceRefresh = false, forceDiscovery = false)
            }
        }
    }

    fun fetchGenres(categoryId: Long? = null) {
        latestGenreEngine.clearCaches()
        if (categoryId != null) {
            latestSnapshotCache.remove(categoryId)
            genreCache.keys.filter { it.startsWith("${categoryId}_") }.forEach {
                genreCache.remove(it)
            }
            genreDiscoveryCache.keys.filter { it.startsWith("${categoryId}:") }.forEach {
                genreDiscoveryCache.remove(it)
            }
            val cat = state.value.categories.find { it.id == categoryId }
            if (cat != null) {
                val feeds = lastFeedSavedSearches[cat.id]
                loadGenresForCategory(cat, feeds, forceRefresh = true, forceDiscovery = true)
            }
        } else {
            genreCache.clear()
            latestSnapshotCache.clear()
            genreDiscoveryCache.clear()
            triggerGenreReloadForAllCategories(forceRefresh = true, forceDiscovery = true)
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
            latestGenreEngine.clearCaches()
            if (categoryId != null) {
                latestSnapshotCache.remove(categoryId)
                val cat = state.value.categories.find { it.id == categoryId }
                if (cat != null) {
                    val feeds = lastFeedSavedSearches[cat.id]
                    loadGenresForCategory(cat, feeds, forceRefresh = true, forceDiscovery = true)
                }
            } else {
                latestSnapshotCache.clear()
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

                        val currentSources = feedSavedSearches.map { it.source }.toSet()
                        val previousSources = lastFeedSavedSearches[category.id]?.map { it.source }?.toSet().orEmpty()
                        val removedSources = previousSources - currentSources
                        val feedsChanged = previousSources != currentSources || !lastFeedSavedSearches.containsKey(category.id)
                        val structuralKey = feedSavedSearches.map { "${it.id}:${it.type}:${it.savedSearch}" }
                        val structureChanged = loadedSourceFeedKeys[category.id] != structuralKey
                        lastFeedSavedSearches[category.id] = feedSavedSearches

                        if (removedSources.isNotEmpty()) {
                            removedSources.forEach { sId ->
                                latestGenreEngine.evictSource(sId)
                            }
                            loadedSourceFeedKeys.remove(category.id)
                            tapResolutionCache.clear()
                            genreCache.clear()
                            genreDiscoveryCache.clear()
                            latestSnapshotCache.remove(category.id)
                            latestMatchesByCategory.remove(category.id)
                        } else if (feedsChanged) {
                            loadedSourceFeedKeys.remove(category.id)
                            tapResolutionCache.clear()
                            genreDiscoveryCache.keys.filter { it.startsWith("${category.id}:") }.forEach {
                                genreDiscoveryCache.remove(it)
                            }
                        }

                        // Automatically prune priority sources if any active priority was from a removed source
                        val currentPriorities = uiPreferences.feedPrioritySourceIds().get()
                        val cleanedPriorities = currentPriorities.filter { it in currentSources }
                        if (cleanedPriorities != currentPriorities) {
                            uiPreferences.feedPrioritySourceIds().set(cleanedPriorities)
                            tapResolutionCache.clear()
                            mutableState.update { it.copy(prioritySourceIds = cleanedPriorities.toImmutableList()) }
                        }

                        // If ALL sources were removed from this category, immediately clean state and exit
                        if (currentSources.isEmpty()) {
                            loadedSourceFeedKeys.remove(category.id)
                            publishLiveFeedSources(category.id, emptyList())
                            mutableState.update { state ->
                                val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                                    .apply { put(category.id, persistentListOf()) }
                                val newGenreMap = state.genreItems.toMutableMap()
                                    .apply { put(category.id, persistentListOf()) }
                                val newItemsMap = state.items.toMutableMap()
                                    .apply { put(category.id, persistentListOf()) }
                                state.copy(
                                    discoveredGenres = newDiscoveredMap.toImmutableMap(),
                                    genreItems = newGenreMap.toImmutableMap(),
                                    items = newItemsMap.toImmutableMap(),
                                )
                            }
                            return@collectLatest
                        }

                        // Publish live source list for this category right away (used by Genre Settings Priority picker)
                        val livePool = resolveSourcePoolForCategory(category.id, feedSavedSearches)
                        publishLiveFeedSources(category.id, livePool)

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
                                            logcat(LogPriority.ERROR, e) {
                                                "Saved search failed for source: ${source.name} (ID: ${source.id})"
                                            }
                                            emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }
                            // Resolve DB writes under Mutex to avoid SQLite "database is locked" contention
                            val animeList = dbWriteMutex.withLock {
                                results.map {
                                    val domainAnime = it.toDomainAnime(source.id)
                                    networkToLocalAnime.await(domainAnime)
                                }.distinctBy { it.id }.toImmutableList()
                            }

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
                                    logActivity.await(
                                        source.id,
                                        ActivityLog.TYPE_FEED_UPDATE,
                                        feedId = feed.id,
                                        count = newItemsCount.toLong(),
                                    )
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

            val sourcePool = resolveSourcePoolForCategory(category.id, categoryFeeds)
            publishLiveFeedSources(category.id, sourcePool)

            // If we're in SOURCES mode and not explicitly fetching, only run lightweight filter discovery
            if (state.value.displayMode != FeedDisplayMode.GENRES && !forceDiscovery) {
                if (sourcePool.isNotEmpty()) {
                    val discoveryKey = "${category.id}:${sourcePool.map { it.id }.sorted().joinToString(",")}"
                    val found = genreDiscoveryCache[discoveryKey]
                        ?: GenreDiscoveryEngine.discoverGenresForSourcesAsync(sourcePool).also {
                            if (it.isNotEmpty()) genreDiscoveryCache[discoveryKey] = it
                        }
                    mutableState.update { state ->
                        val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                        newDiscoveredMap[category.id] = found.toImmutableList()
                        state.copy(discoveredGenres = newDiscoveredMap.toImmutableMap())
                    }
                }
                return@launchIO
            }

            if (sourcePool.isEmpty()) {
                mutableState.update { state ->
                    val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                    newDiscoveredMap[category.id] = persistentListOf()
                    val newGenreMap = state.genreItems.toMutableMap()
                    newGenreMap[category.id] = persistentListOf()
                    state.copy(
                        discoveredGenres = newDiscoveredMap.toImmutableMap(),
                        genreItems = newGenreMap.toImmutableMap(),
                    )
                }
                return@launchIO
            }

            mutableState.update { state ->
                state.copy(
                    discoveringCategories = (state.discoveringCategories + category.id).toImmutableSet(),
                )
            }

            val finalSnapshot: LatestGenreSnapshot = try {
                latestGenreEngine.buildLatestGenreFeed(
                    sources = sourcePool,
                    prioritySourceIds = state.value.prioritySourceIds,
                    forceRefresh = forceRefresh || forceDiscovery,
                    dbWriteMutex = dbWriteMutex,
                    onProgress = { progressSnapshot ->
                        latestSnapshotCache[category.id] = progressSnapshot
                        latestMatchesByCategory[category.id] = progressSnapshot.latestMatchesByTitle
                        if (state.value.displayMode == FeedDisplayMode.GENRES) {
                            rebuildGenreItemsFromSnapshot(
                                categoryId = category.id,
                                snapshot = progressSnapshot,
                                isStillLoading = true,
                            )
                        } else {
                            mutableState.update { state ->
                                val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                                newDiscoveredMap[category.id] = progressSnapshot.discoveredGenres.toImmutableList()
                                state.copy(discoveredGenres = newDiscoveredMap.toImmutableMap())
                            }
                        }
                    },
                )
            } finally {
                mutableState.update { state ->
                    state.copy(
                        discoveringCategories = (state.discoveringCategories - category.id).toImmutableSet(),
                    )
                }
            }

            latestSnapshotCache[category.id] = finalSnapshot
            latestMatchesByCategory[category.id] = finalSnapshot.latestMatchesByTitle

            // Prune dead selected genres that no longer exist in any active source
            val survivingGenreKeys = finalSnapshot.discoveredGenres.map { it.key }.toSet()
            val currentSelected = uiPreferences.feedSelectedGenres().get()
            val cleanedSelected = currentSelected.filter { it in survivingGenreKeys }.toSet()
            if (cleanedSelected != currentSelected) {
                uiPreferences.feedSelectedGenres().set(cleanedSelected)
                mutableState.update { it.copy(selectedGenres = cleanedSelected.toImmutableSet()) }
            }

            if (state.value.displayMode != FeedDisplayMode.GENRES) {
                mutableState.update { state ->
                    val newDiscoveredMap = state.discoveredGenres.toMutableMap()
                    newDiscoveredMap[category.id] = finalSnapshot.discoveredGenres.toImmutableList()
                    state.copy(discoveredGenres = newDiscoveredMap.toImmutableMap())
                }
                return@launchIO
            }

            rebuildGenreItemsFromSnapshot(
                categoryId = category.id,
                snapshot = finalSnapshot,
                isStillLoading = false,
            )
        }
    }

    private fun rebuildGenreItemsFromSnapshot(
        categoryId: Long,
        snapshot: LatestGenreSnapshot,
        isStillLoading: Boolean,
    ): List<DiscoveredGenre> {
        val currentSelectionMode = uiPreferences.feedGenreSelectionMode().get()
        val currentSelectedKeys = uiPreferences.feedSelectedGenres().get()

        val genresByKey = snapshot.discoveredGenres.associateBy { it.key }
        val genresToShow = when (currentSelectionMode) {
            FeedGenreSelectionMode.ALL -> {
                snapshot.discoveredGenres.filter { !snapshot.rowsByGenreKey[it.key].isNullOrEmpty() }
            }
            FeedGenreSelectionMode.SELECTED -> {
                currentSelectedKeys.map { key ->
                    genresByKey[key] ?: DiscoveredGenre(
                        key = key,
                        displayName = GenreNormalizer.toDisplayName(key),
                        animeCount = snapshot.rowsByGenreKey[key]?.size ?: 0,
                    )
                }.sortedBy { it.displayName }
            }
        }

        val emptySelectedForFallback = mutableListOf<DiscoveredGenre>()
        val now = System.currentTimeMillis()

        val genreFeedItems = genresToShow.map { genre ->
            val rowAnime = snapshot.rowsByGenreKey[genre.key].orEmpty()
            val cacheKey = "${categoryId}_${genre.key}"
            val fallbackCached = if (rowAnime.isEmpty()) {
                genreCache[cacheKey]?.takeIf { now - it.first < GENRE_CACHE_TTL_MS }?.second
            } else {
                null
            }
            val effectiveAnime = if (rowAnime.isNotEmpty()) rowAnime else fallbackCached.orEmpty()

            val needsFallback = !isStillLoading &&
                currentSelectionMode == FeedGenreSelectionMode.SELECTED &&
                effectiveAnime.isEmpty() &&
                genre.sourceBindings.isNotEmpty()

            if (needsFallback) {
                emptySelectedForFallback.add(genre)
            }

            val rowSourceCount = maxOf(
                genre.sourceBindings.size,
                effectiveAnime.map { it.source }.distinct().size,
            )

            GenreFeedItem(
                key = genre.key,
                title = genre.displayName,
                sourceCount = rowSourceCount,
                animeList = effectiveAnime.toImmutableList(),
                isLoading = (isStillLoading && effectiveAnime.isEmpty()) || needsFallback,
            )
        }.toImmutableList()

        mutableState.update { state ->
            val newDiscoveredMap = state.discoveredGenres.toMutableMap()
            newDiscoveredMap[categoryId] = snapshot.discoveredGenres.toImmutableList()
            val newGenreMap = state.genreItems.toMutableMap()
            newGenreMap[categoryId] = genreFeedItems
            state.copy(
                discoveredGenres = newDiscoveredMap.toImmutableMap(),
                genreItems = newGenreMap.toImmutableMap(),
            )
        }

        return emptySelectedForFallback
    }

    private suspend fun resolveSourcePoolForCategory(
        categoryId: Long,
        categoryFeeds: List<FeedSavedSearch>,
    ): List<AnimeCatalogueSource> {
        return categoryFeeds
            .mapNotNull { sourceManager.get(it.source) as? AnimeCatalogueSource }
            .distinctBy { it.id }
    }

    private fun publishLiveFeedSources(categoryId: Long, sourcePool: List<AnimeCatalogueSource>) {
        val options = sourcePool
            .distinctBy { it.id }
            .map { FeedSourceOption(sourceId = it.id, sourceName = it.name) }
            .toImmutableList()

        mutableState.update { state ->
            val countMap = state.feedSourceCount.toMutableMap()
            countMap[categoryId] = options.size
            val sourcesMap = state.feedSourcesByCategory.toMutableMap()
            sourcesMap[categoryId] = options
            state.copy(
                feedSourceCount = countMap.toImmutableMap(),
                feedSourcesByCategory = sourcesMap.toImmutableMap(),
            )
        }
    }

    fun loadLazyGenreRow(categoryId: Long, genreKey: String) {
        val currentItems = state.value.genreItems[categoryId] ?: return
        val item = currentItems.find { it.key == genreKey } ?: return
        if (item.animeList.isNotEmpty()) return

        val cached = genreCache["${categoryId}_${genreKey}"]
        if (cached != null && cached.second.isNotEmpty()) {
            mutableState.update { state ->
                val categoryRows = state.genreItems[categoryId] ?: return@update state
                val updated = categoryRows.map {
                    if (it.key == genreKey) {
                        it.copy(animeList = cached.second.toImmutableList(), isLoading = false)
                    } else {
                        it
                    }
                }.toImmutableList()
                val newMap = state.genreItems.toMutableMap()
                newMap[categoryId] = updated
                state.copy(genreItems = newMap.toImmutableMap())
            }
            return
        }

        val genre = state.value.discoveredGenres[categoryId]?.find { it.key == genreKey } ?: return
        if (genre.sourceBindings.isEmpty()) return

        screenModelScope.launchIO {
            genreRowLimiter.withPermit {
                loadSingleGenreRow(categoryId, genre)
            }
        }
    }

    private suspend fun loadSingleGenreRow(categoryId: Long, genre: DiscoveredGenre) {
        val rankedCandidates = mutableListOf<RankedAnime>()
        try {
            coroutineScope {
                val sourceResults = genre.sourceBindings.map { binding ->
                    async {
                        sourceRequestLimiter.withPermit {
                            val source = sourceManager.get(binding.sourceId) as? AnimeCatalogueSource
                                ?: return@withPermit emptyList<RankedAnime>()
                            try {
                                withTimeout(SOURCE_TIMEOUT_MS) {
                                    val filters = source.getFilterList()
                                    val applied = binding.applier.apply(filters)
                                    if (!applied) return@withTimeout emptyList<RankedAnime>()
                                    // Stage 1: Prefer source-native newest/latest/release sort filter
                                    AnimeRecencyResolver.applyLatestSortFilter(filters)
                                    val searchResult = source.getSearchAnime(1, query = "", filters)
                                    val fetchedItems = searchResult.animes.take(24)
                                    dbWriteMutex.withLock {
                                        fetchedItems.mapIndexed { idx, sAnime ->
                                            val domain = sAnime.toDomainAnime(source.id)
                                            val local = networkToLocalAnime.await(domain)
                                            RankedAnime(
                                                anime = local,
                                                sourceOrderRank = idx,
                                                sourceId = source.id,
                                            )
                                        }
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
                    rankedCandidates.addAll(list)
                }
            }

            // Stages 2-4: Deduplicate and sort newest-to-oldest (metadata -> list rank -> AniList fallback)
            val sortedNewestFirst = recencyResolver.sortNewestFirst(rankedCandidates).toImmutableList()

            val cacheKey = "${categoryId}_${genre.key}"
            genreCache[cacheKey] = Pair(System.currentTimeMillis(), sortedNewestFirst)

            mutableState.update { state ->
                val currentCategoryItems = state.genreItems[categoryId] ?: return@update state
                val updatedItems = currentCategoryItems.map { item ->
                    if (item.key == genre.key) {
                        item.copy(
                            animeList = sortedNewestFirst,
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
        val feedSourcesByCategory: ImmutableMap<Long, ImmutableList<FeedSourceOption>> = persistentMapOf(),
        val prioritySourceIds: ImmutableList<Long> = persistentListOf(),
        val resolvingAnime: Anime? = null,
        val resolvingSourceName: String? = null,
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
