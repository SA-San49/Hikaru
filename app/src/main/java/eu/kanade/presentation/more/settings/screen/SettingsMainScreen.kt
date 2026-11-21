package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
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
import eu.kanade.presentation.more.components.CategoryAccent
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

private const val DARK_SURFACE_DELTA = 0.05f
private const val LIGHT_SURFACE_DELTA = 0.02f
private const val HSL_CHANNELS = 3

private val SettingsListPaddingHorizontal = 16.dp
private val SettingsListPaddingTop = 12.dp
private val SettingsListPaddingBottom = 24.dp
private val SettingsSectionSpacing = 18.dp

object SettingsMainScreen : Screen() {

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

        val sections = remember {
            listOf(
                Section(
                    title = "User Interface",
                    accent = CategoryAccent.INDIGO,
                    items = listOf(
                        Item(
                            titleRes = MR.strings.pref_category_appearance,
                            subtitleRes = MR.strings.pref_appearance_summary,
                            icon = Icons.Outlined.Palette,
                            accent = CategoryAccent.INDIGO,
                            screen = SettingsAppearanceScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_library,
                            subtitleRes = MR.strings.pref_library_summary,
                            icon = Icons.Outlined.CollectionsBookmark,
                            accent = CategoryAccent.EMERALD,
                            screen = SettingsLibraryScreen,
                        ),
                    ),
                ),
                Section(
                    title = "Core Features",
                    accent = CategoryAccent.VIOLET,
                    items = listOf(
                        Item(
                            titleRes = MR.strings.pref_category_schedule,
                            subtitleRes = MR.strings.pref_category_schedule_summary,
                            icon = Icons.Outlined.DateRange,
                            accent = CategoryAccent.AMBER,
                            screen = SettingsScheduleScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_downloads,
                            subtitleRes = MR.strings.pref_downloads_summary,
                            icon = Icons.Outlined.GetApp,
                            accent = CategoryAccent.EMERALD,
                            screen = SettingsDownloadScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_tracking,
                            subtitleRes = MR.strings.pref_tracking_summary,
                            icon = Icons.Outlined.Sync,
                            accent = CategoryAccent.ROSE,
                            screen = SettingsTrackingScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_ai,
                            subtitleRes = MR.strings.pref_ai_summary,
                            icon = Icons.Default.AutoAwesome,
                            accent = CategoryAccent.VIOLET,
                            screen = SettingsAiScreen,
                        ),
                    ),
                ),
                Section(
                    title = "Connections",
                    accent = CategoryAccent.CYAN,
                    items = listOf(
                        Item(
                            titleRes = KMR.strings.pref_category_connections,
                            subtitleRes = KMR.strings.pref_connections_summary,
                            icon = Icons.Outlined.Link,
                            accent = CategoryAccent.CYAN,
                            screen = SettingsConnectionsScreen,
                        ),
                        Item(
                            titleRes = MR.strings.browse,
                            subtitleRes = MR.strings.pref_browse_summary,
                            icon = Icons.Outlined.Explore,
                            accent = CategoryAccent.INDIGO,
                            screen = SettingsBrowseScreen,
                        ),
                    ),
                ),
                Section(
                    title = "System",
                    accent = CategoryAccent.SLATE,
                    items = listOf(
                        Item(
                            titleRes = MR.strings.label_data_storage,
                            subtitleRes = MR.strings.pref_backup_summary,
                            icon = Icons.Outlined.Storage,
                            accent = CategoryAccent.CYAN,
                            screen = SettingsDataScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_security,
                            subtitleRes = MR.strings.pref_security_summary,
                            icon = Icons.Outlined.Security,
                            accent = CategoryAccent.ROSE,
                            screen = SettingsSecurityScreen,
                        ),
                        Item(
                            titleRes = MR.strings.pref_category_advanced,
                            subtitleRes = MR.strings.pref_advanced_summary,
                            icon = Icons.Outlined.Code,
                            accent = CategoryAccent.SLATE,
                            screen = SettingsAdvancedScreen,
                        ),
                    ),
                ),
                Section(
                    title = "About",
                    accent = CategoryAccent.AMBER,
                    items = listOf(
                        Item(
                            titleRes = MR.strings.pref_category_about,
                            formatSubtitle = {
                                "${stringResource(MR.strings.app_name)} ${
                                    AboutScreen.getVersionName(withBuildDate = false)
                                }"
                            },
                            icon = Icons.Outlined.Info,
                            accent = CategoryAccent.AMBER,
                            screen = AboutScreen,
                        ),
                    ),
                ),
            )
        }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_settings),
                    navigateUp = backPress::invoke,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = Icons.Outlined.Search,
                                    onClick = { navigator.navigate(SettingsSearchScreen(), twoPane) },
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
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(
                        start = SettingsListPaddingHorizontal,
                        end = SettingsListPaddingHorizontal,
                        top = SettingsListPaddingTop,
                        bottom = SettingsListPaddingBottom + contentPadding.calculateBottomPadding(),
                    ),
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
                ) {
                    sections.forEach { section ->
                        item(key = "settings-category-${section.title}") {
                            MoreSection(
                                title = section.title,
                                accentColor = section.accent.resolveColor(),
                            ) {
                                section.items.forEachIndexed { index, item ->
                                    val onClick = remember(item.screen, twoPane, navigator) {
                                        { navigator.navigate(item.screen, twoPane) }
                                    }
                                    val isSelected = twoPane && activeScreenClass == item.screen::class
                                    MoreItem(
                                        title = stringResource(item.titleRes),
                                        subtitle = item.formatSubtitle(),
                                        icon = item.icon,
                                        iconTint = item.accent.resolveColor(),
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
        if (twoPane) replace(screen) else push(screen)
    }

    private data class Section(
        val title: String,
        val accent: CategoryAccent,
        val items: List<Item>,
    )

    data class Item(
        val titleRes: StringResource,
        val subtitleRes: StringResource? = null,
        val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
        val icon: ImageVector,
        val accent: CategoryAccent = CategoryAccent.PRIMARY,
        val screen: VoyagerScreen,
    )
}
