package eu.kanade.tachiyomi.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.home.genre.DiscoveredGenre
import eu.kanade.tachiyomi.ui.home.genre.FeedDisplayMode
import eu.kanade.tachiyomi.ui.home.genre.FeedGenreSelectionMode
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedItem
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedSettingsSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.SkeletonAnimeCard
import tachiyomi.presentation.core.components.SkeletonFeedIsland
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun FeedScreen(
    screenModel: FeedScreenModel,
    onAnimeClick: (Anime, Long?) -> Unit,
    onAddSourceClick: () -> Unit,
    onSeeAllClick: (FeedScreenModel.FeedItem) -> Unit,
    onGenreSeeAllClick: (GenreFeedItem) -> Unit = {},
    contentPadding: PaddingValues,
    usePanorama: Boolean,
) {
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showGenreSettings by remember { mutableStateOf(false) }

    if (state.categories.isEmpty()) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    val visibleCategories = remember(state.categories, state.items, state.displayMode) {
        if (state.categories.size <= 1) {
            state.categories
        } else {
            state.categories.filterNot { category ->
                state.displayMode == FeedDisplayMode.SOURCES &&
                    category.name == "Global" &&
                    state.items[category.id].isNullOrEmpty()
            }
        }
    }

    val pagerState = rememberPagerState { visibleCategories.size }
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val currentCategory = visibleCategories.getOrNull(currentPage)
    val isCurrentCategoryDiscovering = currentCategory != null &&
        currentCategory.id in state.discoveringCategories

    if (showGenreSettings) {
        val discoveredForCategory = currentCategory?.let { state.discoveredGenres[it.id] } ?: emptyList()
        val sourceCount = currentCategory?.let { state.feedSourceCount[it.id] } ?: 0
        val feedSources = currentCategory?.let { state.feedSourcesByCategory[it.id] } ?: emptyList()
        GenreFeedSettingsSheet(
            onDismissRequest = { showGenreSettings = false },
            selectionMode = state.genreSelectionMode,
            onSelectionModeChange = { screenModel.setGenreSelectionMode(it) },
            discoveredGenres = discoveredForCategory,
            selectedGenres = state.selectedGenres,
            onToggleGenre = { screenModel.toggleGenreSelected(it) },
            onSelectAll = { currentCategory?.let { screenModel.selectAllGenres(it.id) } },
            onDeselectAll = { screenModel.deselectAllGenres() },
            onFetchGenres = { screenModel.fetchGenres(currentCategory?.id) },
            isDiscovering = isCurrentCategoryDiscovering,
            sourceCount = sourceCount,
            feedSources = feedSources,
            prioritySourceIds = state.prioritySourceIds,
            onTogglePrioritySource = screenModel::togglePrioritySource,
            onClearPrioritySources = screenModel::clearPrioritySources,
        )
    }

    val resolvingAnime = state.resolvingAnime
    var showResolvingDialog by remember(resolvingAnime) { mutableStateOf(false) }
    LaunchedEffect(resolvingAnime) {
        if (resolvingAnime != null) {
            delay(180L)
            showResolvingDialog = true
        } else {
            showResolvingDialog = false
        }
    }
    if (resolvingAnime != null && showResolvingDialog) {
        AlertDialog(
            onDismissRequest = {
                screenModel.cancelTapResolution { fallback -> onAnimeClick(fallback, null) }
            },
            title = {
                Text(
                    text = "Finding Priority Source",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = resolvingAnime.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.resolvingSourceName?.let { "Checking $it..." }
                                ?: "Searching feed sources...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        screenModel.cancelTapResolution { fallback -> onAnimeClick(fallback, null) }
                    },
                ) {
                    Text("Open Original")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        // Compact modern control bar
        FeedModeControlBar(
            displayMode = state.displayMode,
            onDisplayModeChange = screenModel::setDisplayMode,
            isDiscovering = isCurrentCategoryDiscovering,
            selectedCount = state.selectedGenres.size,
            selectionMode = state.genreSelectionMode,
            onFetchGenres = { screenModel.fetchGenres(currentCategory?.id) },
            onOpenGenreSettings = { showGenreSettings = true },
        )

        // Category tabs if multiple categories exist
        if (visibleCategories.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = currentPage.coerceIn(0, visibleCategories.lastIndex),
                edgePadding = 16.dp,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[currentPage.coerceIn(0, tabPositions.lastIndex)]),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            ) {
                visibleCategories.forEachIndexed { index, category ->
                    Tab(
                        selected = currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            key = { page -> visibleCategories.getOrNull(page)?.id ?: page },
        ) { page ->
            val category = visibleCategories.getOrNull(page) ?: return@HorizontalPager
            val isDiscoveringThisCat = category.id in state.discoveringCategories
            val listPadding = remember(contentPadding) {
                PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                )
            }

            PullRefresh(
                refreshing = state.displayMode == FeedDisplayMode.GENRES && isDiscoveringThisCat,
                onRefresh = { screenModel.refresh(category.id) },
                enabled = true,
            ) {
                if (state.displayMode == FeedDisplayMode.SOURCES) {
                    val items = state.items[category.id]
                    if (items == null) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(3, key = { "skeleton-src-$it" }) {
                                SkeletonFeedIsland()
                            }
                        }
                    } else if (items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = contentPadding.calculateBottomPadding()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyScreen(
                                stringRes = SYMR.strings.feed_tab_empty,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = onAddSourceClick,
                                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                            ) {
                                Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(text = "Add Sources")
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(
                                items = items,
                                key = { _, it -> "feed-${it.feed.id}" },
                                contentType = { _, _ -> "feed_island" },
                            ) { _, item ->
                                FeedIsland(
                                    item = item,
                                    onAnimeClick = { onAnimeClick(it, item.feed.id) },
                                    onSeeAllClick = { onSeeAllClick(item) },
                                    usePanorama = usePanorama,
                                )
                            }
                        }
                    }
                } else {
                    val genreItems = state.genreItems[category.id]
                    val discoveredForCat = state.discoveredGenres[category.id].orEmpty()
                    val sourceCount = state.feedSourceCount[category.id] ?: 0

                    when {
                        isDiscoveringThisCat && (genreItems == null || genreItems.isEmpty()) -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = listPadding,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(3, key = { "skeleton-genre-$it" }) {
                                    SkeletonFeedIsland()
                                }
                            }
                        }
                        genreItems == null || genreItems.isEmpty() -> {
                            EmptyOrSelectGenresView(
                                categoryName = category.name,
                                selectionMode = state.genreSelectionMode,
                                discoveredGenres = discoveredForCat,
                                selectedGenres = state.selectedGenres,
                                sourceCount = sourceCount,
                                isDiscovering = isDiscoveringThisCat,
                                onToggleGenre = screenModel::toggleGenreSelected,
                                onFetchGenres = { screenModel.fetchGenres(category.id) },
                                onOpenSettings = { showGenreSettings = true },
                                onAddSourceClick = onAddSourceClick,
                                bottomPadding = contentPadding.calculateBottomPadding(),
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = listPadding,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                item(key = "genre-scope-bar") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${genreItems.size} genre${if (genreItems.size == 1) "" else "s"} • $sourceCount source${if (sourceCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = if (state.genreSelectionMode == FeedGenreSelectionMode.ALL) "All genres" else "Selected filter",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                itemsIndexed(
                                    items = genreItems,
                                    key = { _, it -> "genre-${it.key}" },
                                    contentType = { _, _ -> "genre_feed_island" },
                                ) { _, item ->
                                    GenreFeedIsland(
                                        item = item,
                                        onAnimeClick = { anime ->
                                            screenModel.resolveGenreAnimeClick(anime, category.id) { resolved ->
                                                onAnimeClick(resolved, null)
                                            }
                                        },
                                        onSeeAllClick = { onGenreSeeAllClick(item) },
                                        usePanorama = usePanorama,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedModeControlBar(
    displayMode: FeedDisplayMode,
    onDisplayModeChange: (FeedDisplayMode) -> Unit,
    isDiscovering: Boolean,
    selectedCount: Int,
    selectionMode: FeedGenreSelectionMode,
    onFetchGenres: () -> Unit,
    onOpenGenreSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small, sleek capsule mode switcher
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            border = BorderStroke(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactModePill(
                    label = "Sources",
                    icon = Icons.Outlined.Public,
                    selected = displayMode == FeedDisplayMode.SOURCES,
                    onClick = { onDisplayModeChange(FeedDisplayMode.SOURCES) },
                )
                CompactModePill(
                    label = "Genres",
                    icon = Icons.Outlined.AutoAwesome,
                    selected = displayMode == FeedDisplayMode.GENRES,
                    onClick = { onDisplayModeChange(FeedDisplayMode.GENRES) },
                )
            }
        }

        if (displayMode == FeedDisplayMode.GENRES) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Small Fetch Genres pill button
                Surface(
                    onClick = onFetchGenres,
                    enabled = !isDiscovering,
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isDiscovering) {
                            SpinningSyncIcon(
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = "Fetch Genres",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = if (isDiscovering) "Fetching" else "Fetch",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Small Genre Settings pill button
                Surface(
                    onClick = onOpenGenreSettings,
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Genre settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        if (selectionMode == FeedGenreSelectionMode.SELECTED && selectedCount > 0) {
                            Text(
                                text = "$selectedCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpinningSyncIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "genre_fetch_spin")
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    Icon(
        imageVector = Icons.Outlined.Sync,
        contentDescription = "Fetch Genres",
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = spin },
    )
}

@Composable
private fun CompactModePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            Color.Transparent
        },
        label = "pill_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "pill_fg",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(7.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyOrSelectGenresView(
    categoryName: String,
    selectionMode: FeedGenreSelectionMode,
    discoveredGenres: List<DiscoveredGenre>,
    selectedGenres: Set<String>,
    sourceCount: Int,
    isDiscovering: Boolean,
    onToggleGenre: (String) -> Unit,
    onFetchGenres: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddSourceClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selectionMode == FeedGenreSelectionMode.SELECTED && discoveredGenres.isNotEmpty()) {
            item(key = "pick-genres-card") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary,
                                            ),
                                        ),
                                    ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Select Genres to Watch",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${discoveredGenres.size} live genres from $sourceCount feed source${if (sourceCount == 1) "" else "s"} • None active yet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            discoveredGenres.take(36).forEach { genre ->
                                val active = genre.key in selectedGenres
                                Surface(
                                    onClick = { onToggleGenre(genre.key) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    border = BorderStroke(
                                        width = 0.75.dp,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        },
                                    ),
                                ) {
                                    Text(
                                        text = "+ ${genre.displayName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onFetchGenres,
                                enabled = !isDiscovering,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Fetch")
                            }
                            Button(
                                onClick = onOpenSettings,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Browse (${discoveredGenres.size})")
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "no-genres-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Σ(ಠ_ಠ)",
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                    Text(
                        text = if (sourceCount == 0) {
                            "No sources in $categoryName feed yet. Add sources from Browse to discover live genres."
                        } else {
                            "No genre or tag filters found yet for your $sourceCount source${if (sourceCount == 1) "" else "s"} in $categoryName."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onFetchGenres,
                            enabled = !isDiscovering,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(imageVector = Icons.Outlined.Sync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isDiscovering) "Fetching..." else "Fetch Genres")
                        }
                        OutlinedButton(
                            onClick = onAddSourceClick,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Sources")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedIsland(
    item: FeedScreenModel.FeedItem,
    onAnimeClick: (Anime) -> Unit,
    onSeeAllClick: () -> Unit,
    usePanorama: Boolean,
) {
    val badgeText = remember(item.feed.type, item.savedSearch?.id) {
        item.savedSearch?.name ?: FeedSavedSearch.Type.from(item.feed.type).name
    }
    val rowState = rememberLazyListState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            FuturisticRowHeader(
                title = item.source.name,
                badge = badgeText,
                isLoading = item.isLoading,
                onSeeAllClick = onSeeAllClick,
            )

            Spacer(Modifier.height(6.dp))

            if (item.animeList.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(4) {
                        SkeletonAnimeCard(width = 108.dp)
                    }
                }
            } else {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = item.animeList,
                        key = { _, anime -> "anime-${item.feed.id}-${anime.id}" },
                        contentType = { _, _ -> "anime_card" },
                    ) { _, anime ->
                        val itemClick = remember(anime.id, onAnimeClick) { { onAnimeClick(anime) } }
                        FeedCard(
                            anime = anime,
                            onClick = itemClick,
                            usePanorama = usePanorama,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreFeedIsland(
    item: GenreFeedItem,
    onAnimeClick: (Anime) -> Unit,
    onSeeAllClick: () -> Unit,
    usePanorama: Boolean,
) {
    val rowState = rememberLazyListState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            FuturisticRowHeader(
                title = item.title,
                badge = if (item.animeList.isNotEmpty()) {
                    "${item.animeList.size} latest"
                } else {
                    "${item.sourceCount} src"
                },
                isLoading = item.isLoading,
                onSeeAllClick = onSeeAllClick,
            )

            Spacer(Modifier.height(6.dp))

            if (item.animeList.isEmpty()) {
                if (item.isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(4) {
                            SkeletonAnimeCard(width = 108.dp)
                        }
                    }
                } else {
                    Text(
                        text = item.errorMessage ?: "No anime found for this genre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = item.animeList,
                        key = { _, anime -> "genre-anime-${item.key}-${anime.id}" },
                        contentType = { _, _ -> "anime_card" },
                    ) { _, anime ->
                        val itemClick = remember(anime.id, onAnimeClick) { { onAnimeClick(anime) } }
                        FeedCard(
                            anime = anime,
                            onClick = itemClick,
                            usePanorama = usePanorama,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FuturisticRowHeader(
    title: String,
    badge: String,
    isLoading: Boolean,
    onSeeAllClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Futuristic gradient accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            Surface(
                onClick = onSeeAllClick,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "See all ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedCard(
    anime: Anime,
    onClick: () -> Unit,
    usePanorama: Boolean,
) {
    val (entry, ratio) = AnimeCover.getEntry(anime.id, usePanoramaOverride = usePanorama)
    val width = if (entry == AnimeCover.Panorama) 208.dp else 112.dp
    val bottomGradient = remember {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.55f to Color.Transparent,
            1.0f to Color(0xCC07090F),
        )
    }

    Column(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .border(
                    width = 0.75.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(13.dp),
                ),
        ) {
            entry(
                data = anime,
                onClick = null,
                modifier = Modifier.fillMaxWidth(),
                ratio = ratio,
                shouldExtractColor = false,
                shouldUpdateRatio = usePanorama,
                shape = RoundedCornerShape(13.dp),
            )

            // Subtle futuristic vignette & watch badge
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(bottomGradient),
            )

            if (anime.favorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Text(
            text = anime.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .height(32.dp),
        )
    }
}
