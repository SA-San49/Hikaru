package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.settings.screen.SettingsSearchScreen
import eu.kanade.presentation.more.settings.screen.player.custombutton.PlayerSettingsCustomButtonScreen
import eu.kanade.presentation.more.settings.screen.player.editor.PlayerSettingsEditorScreen
import eu.kanade.presentation.more.settings.screen.player.layout.PlayerSettingsLayoutMainScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

private const val DARK_SURFACE_DELTA = 0.05f
private const val LIGHT_SURFACE_DELTA = 0.02f
private const val HSL_CHANNELS = 3

private val PlayerListHorizontalPadding = 16.dp
private val PlayerListTopPadding = 12.dp
private val PlayerListBottomPadding = 24.dp
private val PlayerSectionSpacing = 18.dp

object PlayerSettingsMainScreen : Screen() {

    @Composable
    override fun Content() {
        Content(twoPane = false)
    }

    @Composable
    private fun getPalerSurface(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val dark = isSystemInDarkTheme()
        return remember(surface, dark) {
            val arr = FloatArray(HSL_CHANNELS)
            ColorUtils.colorToHSL(surface.toArgb(), arr)
            arr[2] = if (dark) {
                arr[2] - DARK_SURFACE_DELTA
            } else {
                arr[2] + LIGHT_SURFACE_DELTA
            }.coerceIn(0f, 1f)
            Color.hsl(arr[0], arr[1], arr[2])
        }
    }

    @Composable
    @Suppress("LongMethod")
    fun Content(twoPane: Boolean) {
        val navigator = LocalNavigator.currentOrThrow
        val backPress = LocalBackPress.currentOrThrow
        val containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.background
        val topBarState = rememberTopAppBarState()
        val accentColor = LocalSettingsAccentColor.current
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_player_settings),
                    navigateUp = backPress::invoke,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = Icons.Outlined.Search,
                                    onClick = { navigator.navigate(SettingsSearchScreen(true), twoPane) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = containerColor,
            hazeEnabled = hazeEnabled,
            content = { contentPadding ->
                val state = rememberLazyListState()
                val activeScreenClass = if (twoPane) navigator.items.firstOrNull()?.let { it::class } else null
                val selectedSectionIndex = if (twoPane && activeScreenClass != null) {
                    sections.indexOfFirst { section ->
                        section.items.any { it.screen::class == activeScreenClass }
                    }
                } else {
                    -1
                }

                if (twoPane && selectedSectionIndex >= 0) {
                    LaunchedEffect(selectedSectionIndex) {
                        state.animateScrollToItem(selectedSectionIndex)
                        if (selectedSectionIndex > 0) {
                            topBarState.contentOffset = topBarState.heightOffsetLimit
                        }
                    }
                }

                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(
                        start = PlayerListHorizontalPadding,
                        end = PlayerListHorizontalPadding,
                        top = PlayerListTopPadding,
                        bottom = PlayerListBottomPadding + contentPadding.calculateBottomPadding(),
                    ),
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    verticalArrangement = Arrangement.spacedBy(PlayerSectionSpacing),
                ) {
                    sections.forEach { section ->
                        item(key = "player-section-${section.title}") {
                            MoreSection(
                                title = section.title,
                                accentColor = accentColor,
                            ) {
                                section.items.forEachIndexed { index, item ->
                                    val isSelected = twoPane && activeScreenClass == item.screen::class
                                    val onClick = remember(item.screen, twoPane, navigator) {
                                        { navigator.navigate(item.screen, twoPane) }
                                    }
                                    MoreItem(
                                        title = stringResource(item.titleRes),
                                        subtitle = item.formatSubtitle(),
                                        icon = item.icon,
                                        iconTint = accentColor,
                                        selected = isSelected,
                                        showDivider = index < section.items.lastIndex,
                                        onClick = onClick,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    private fun Navigator.navigate(screen: VoyagerScreen, twoPane: Boolean) {
        if (lastItem.key == screen.key) return
        if (twoPane) replaceAll(screen) else push(screen)
    }

    private data class Section(
        val title: String,
        val items: List<Item>,
    )

    private data class Item(
        val titleRes: StringResource,
        val subtitleRes: StringResource? = null,
        val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
        val icon: ImageVector,
        val screen: VoyagerScreen,
    )

    private val sections = listOf(
        Section(
            title = "Playback & Controls",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_player_internal,
                    subtitleRes = MR.strings.pref_player_internal_summary,
                    icon = Icons.Outlined.PlayCircleOutline,
                    screen = PlayerSettingsPlayerScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_layout,
                    subtitleRes = MR.strings.pref_player_layout_summary,
                    icon = Icons.Outlined.Dashboard,
                    screen = PlayerSettingsLayoutMainScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_gestures,
                    subtitleRes = MR.strings.pref_player_gestures_summary,
                    icon = Icons.Outlined.Gesture,
                    screen = PlayerSettingsGesturesScreen,
                ),
            ),
        ),
        Section(
            title = "Media & Tracks",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_player_decoder,
                    subtitleRes = MR.strings.pref_player_decoder_summary,
                    icon = Icons.Outlined.Memory,
                    screen = PlayerSettingsDecoderScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_subtitle,
                    subtitleRes = MR.strings.pref_player_subtitle_summary,
                    icon = Icons.Outlined.Subtitles,
                    screen = PlayerSettingsSubtitleScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_audio,
                    subtitleRes = MR.strings.pref_player_audio_summary,
                    icon = Icons.Outlined.Audiotrack,
                    screen = PlayerSettingsAudioScreen,
                ),
            ),
        ),
        Section(
            title = "Customization & Engine",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_player_custom_button,
                    subtitleRes = MR.strings.pref_player_custom_button_summary,
                    icon = Icons.Outlined.Terminal,
                    screen = PlayerSettingsCustomButtonScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_editor,
                    subtitleRes = MR.strings.pref_player_editor_summary,
                    icon = Icons.Outlined.EditNote,
                    screen = PlayerSettingsEditorScreen,
                ),
                Item(
                    titleRes = MR.strings.pref_player_advanced,
                    subtitleRes = MR.strings.pref_player_advanced_summary,
                    icon = Icons.Outlined.Code,
                    screen = PlayerSettingsAdvancedScreen,
                ),
            ),
        ),
    )
}
