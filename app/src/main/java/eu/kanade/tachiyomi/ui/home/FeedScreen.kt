package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.home.genre.FeedDisplayMode
import eu.kanade.tachiyomi.ui.home.genre.FeedGenreSelectionMode
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedItem
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedSettingsSheet
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.SkeletonAnimeCard
import tachiyomi.presentation.core.components.SkeletonFeedIsland
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

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

    val visibleCategories = remember(state.categories, state.items, state.genreItems, state.displayMode) {
        if (state.categories.size <= 1) {
            state.categories
        } else {
            state.categories.filterNot { category ->
                if (state.displayMode == FeedDisplayMode.SOURCES) {
                    category.name == "Global" && state.items[category.id].isNullOrEmpty()
                } else {
                    category.name == "Global" && state.genreItems[category.id].isNullOrEmpty()
                }
            }
        }
    }

    val pagerState = rememberPagerState { visibleCategories.size }
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    if (showGenreSettings) {
        val currentCategory = visibleCategories.getOrNull(currentPage)
        val discoveredForCategory = currentCategory?.let { state.discoveredGenres[it.id] } ?: emptyList()
        GenreFeedSettingsSheet(
            onDismissRequest = { showGenreSettings = false },
            selectionMode = state.genreSelectionMode,
            onSelectionModeChange = { screenModel.setGenreSelectionMode(it) },
            discoveredGenres = discoveredForCategory,
            selectedGenres = state.selectedGenres,
            onToggleGenre = { screenModel.toggleGenreSelected(it) },
            onSelectAll = { currentCategory?.let { screenModel.selectAllGenres(it.id) } },
            onDeselectAll = { screenModel.deselectAllGenres() },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        // Mode switch row: Sources | Genres + Genre Settings icon button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MultiChoiceSegmentedButtonRow(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                SegmentedButton(
                    checked = state.displayMode == FeedDisplayMode.SOURCES,
                    onCheckedChange = { screenModel.setDisplayMode(FeedDisplayMode.SOURCES) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Sources", maxLines = 1)
                }
                SegmentedButton(
                    checked = state.displayMode == FeedDisplayMode.GENRES,
                    onCheckedChange = { screenModel.setDisplayMode(FeedDisplayMode.GENRES) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Genres", maxLines = 1)
                }
            }

            if (state.displayMode == FeedDisplayMode.GENRES) {
                IconButton(
                    onClick = { showGenreSettings = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Genre settings",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

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
            val listPadding = remember(contentPadding) {
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                )
            }

            if (state.displayMode == FeedDisplayMode.SOURCES) {
                // Sources mode
                val items = state.items[category.id]
                if (items == null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
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
                        )
                        Button(
                            onClick = onAddSourceClick,
                            modifier = Modifier.padding(top = 16.dp),
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
                        verticalArrangement = Arrangement.spacedBy(24.dp),
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
                // Genres mode
                val genreItems = state.genreItems[category.id]
                if (genreItems == null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(3, key = { "skeleton-genre-$it" }) {
                            SkeletonFeedIsland()
                        }
                    }
                } else if (genreItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyScreen(
                            message = if (state.genreSelectionMode == FeedGenreSelectionMode.SELECTED) {
                                "No genres selected. Open genre settings to choose which genres appear."
                            } else {
                                "No genre filters discovered from the feed sources in ${category.name}."
                            },
                        )
                        Row(
                            modifier = Modifier.padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { showGenreSettings = true }) {
                                Icon(imageVector = Icons.Outlined.Tune, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Genre Settings")
                            }
                            Button(onClick = onAddSourceClick) {
                                Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Sources")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        // Scope documentation header
                        item(key = "genre-scope-doc") {
                            Text(
                                text = "Showing merged genres from feed sources in ${category.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        itemsIndexed(
                            items = genreItems,
                            key = { _, it -> "genre-${it.key}" },
                            contentType = { _, _ -> "genre_feed_island" },
                        ) { _, item ->
                            GenreFeedIsland(
                                item = item,
                                onAnimeClick = { onAnimeClick(it, null) },
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

@Composable
private fun FeedIsland(
    item: FeedScreenModel.FeedItem,
    onAnimeClick: (Anime) -> Unit,
    onSeeAllClick: () -> Unit,
    usePanorama: Boolean,
) {
    val title = remember(item.feed.id, item.feed.type, item.savedSearch?.id) {
        if (item.savedSearch != null) {
            "${item.source.name} (${item.savedSearch.name})"
        } else {
            "${item.source.name} (${tachiyomi.domain.source.model.FeedSavedSearch.Type.from(item.feed.type).name})"
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(
                    onClick = onSeeAllClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (item.animeList.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(4) {
                        SkeletonAnimeCard(width = 100.dp)
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = item.animeList,
                        key = { _, anime -> "anime-${item.feed.id}-${anime.id}" },
                        contentType = { _, _ -> "anime_card" },
                    ) { _, anime ->
                        FeedCard(
                            anime = anime,
                            onClick = { onAnimeClick(anime) },
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.sourceCount} source${if (item.sourceCount > 1) "s" else ""} merged",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(
                    onClick = onSeeAllClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (item.animeList.isEmpty()) {
                if (item.isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(4) {
                            SkeletonAnimeCard(width = 100.dp)
                        }
                    }
                } else {
                    Text(
                        text = item.errorMessage ?: "No anime found for this genre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = item.animeList,
                        key = { _, anime -> "genre-anime-${item.key}-${anime.id}" },
                        contentType = { _, _ -> "anime_card" },
                    ) { _, anime ->
                        FeedCard(
                            anime = anime,
                            onClick = { onAnimeClick(anime) },
                            usePanorama = usePanorama,
                        )
                    }
                }
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
    val width = if (entry == AnimeCover.Panorama) 200.dp else 100.dp
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entry(
            data = anime,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            ratio = ratio,
        )
        Text(
            text = anime.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(32.dp),
        )
    }
}
