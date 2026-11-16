package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaMode
import eu.kanade.presentation.more.settings.widget.PanoramaModeToggle
import eu.kanade.presentation.more.settings.widget.resolve
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.home.genre.GenreFeedItem
import eu.kanade.tachiyomi.ui.home.genre.SourceGenreBinding
import kotlinx.coroutines.launch
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun feedTab(): Tab = FeedTab

data object FeedTab : Tab {
    override fun isEnabled(): Boolean {
        return true
    }

    override val options: TabOptions
        @Composable
        get() {
            val title = SYMR.strings.feed
            return TabOptions(
                index = 1u,
                title = stringResource(title),
                icon = painterResource(R.drawable.ic_dynamic_feed_24dp),
            )
        }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref() as androidx.compose.runtime.State<Boolean>
        val feedMode by uiPreferences.feedPanoramaMode().collectAsStatePref() as androidx.compose.runtime.State<PanoramaMode>
        val effectivePanorama = remember(globalPanorama, feedMode) { feedMode.resolve(globalPanorama) }
        val screenModel = rememberScreenModel { FeedScreenModel() }

        Scaffold(
            topBar = {
                eu.kanade.presentation.components.AppBar(
                    title = stringResource(SYMR.strings.feed),
                    actions = {
                        IconButton(onClick = { screenModel.refresh() }) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh feed",
                            )
                        }
                        PanoramaModeToggle(
                            panoramaMode = feedMode,
                            globalPanorama = globalPanorama,
                            onPanoramaModeChange = { next ->
                                uiPreferences.feedPanoramaMode().set(next)
                            },
                        )
                        IconButton(onClick = { navigator.push(FeedManageScreen()) }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Edit Feed",
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            Content(contentPadding, effectivePanorama, screenModel)
        }
    }

    @Composable
    fun Content(
        contentPadding: PaddingValues,
        usePanorama: Boolean,
        screenModel: FeedScreenModel = rememberScreenModel { FeedScreenModel() },
    ) {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val scope = rememberCoroutineScope()
        var genreToBrowse by remember { mutableStateOf<GenreFeedItem?>(null) }

        if (genreToBrowse != null) {
            val bindings = screenModel.getBindingsForGenre(genreToBrowse!!.key)
            if (bindings.size == 1) {
                val singleBinding = bindings.first()
                navigator.push(
                    eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
                        sourceId = singleBinding.sourceId,
                    ),
                )
                genreToBrowse = null
            } else if (bindings.isNotEmpty()) {
                GenreSourceChooserDialog(
                    genreTitle = genreToBrowse!!.title,
                    bindings = bindings,
                    onDismiss = { genreToBrowse = null },
                    onSelectSource = { sourceId ->
                        genreToBrowse = null
                        navigator.push(
                            eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
                                sourceId = sourceId,
                            ),
                        )
                    },
                )
            } else {
                genreToBrowse = null
            }
        }

        FeedScreen(
            screenModel = screenModel,
            onAnimeClick = { anime, feedId ->
                screenModel.onAnimeClicked(anime, feedId)
                navigator.push(AnimeScreen(anime.id))
            },
            onAddSourceClick = {
                scope.launch {
                    tabNavigator.current = BrowseTab
                }
            },
            onSeeAllClick = { item ->
                val type = tachiyomi.domain.source.model.FeedSavedSearch.Type.from(item.feed.type)
                val screen = when (type) {
                    tachiyomi.domain.source.model.FeedSavedSearch.Type.Latest ->
                        eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
                            sourceId = item.source.id,
                            listingQuery = tachiyomi.domain.source.interactor.GetRemoteAnime.QUERY_LATEST,
                        )
                    tachiyomi.domain.source.model.FeedSavedSearch.Type.Popular ->
                        eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
                            sourceId = item.source.id,
                            listingQuery = tachiyomi.domain.source.interactor.GetRemoteAnime.QUERY_POPULAR,
                        )
                    tachiyomi.domain.source.model.FeedSavedSearch.Type.SavedSearch ->
                        eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
                            sourceId = item.source.id,
                            savedSearchId = item.savedSearch?.id,
                        )
                }
                navigator.push(screen)
            },
            onGenreSeeAllClick = { genreItem ->
                genreToBrowse = genreItem
            },
            contentPadding = contentPadding,
            usePanorama = usePanorama,
        )
    }

    @Composable
    private fun GenreSourceChooserDialog(
        genreTitle: String,
        bindings: List<SourceGenreBinding>,
        onDismiss: () -> Unit,
        onSelectSource: (Long) -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Browse $genreTitle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Select a source to browse this genre:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(bindings, key = { it.sourceId }) { binding ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSource(binding.sourceId) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = binding.sourceName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }
}
