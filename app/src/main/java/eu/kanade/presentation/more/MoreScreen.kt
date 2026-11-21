package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.components.MoreSwitchItem
import eu.kanade.presentation.more.settings.screen.NavigationSettingsScreen
import eu.kanade.presentation.more.settings.screen.ai.AiAssistantScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.ui.stats.InfrastructureScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val ScreenHorizontalPadding = 16.dp
private val ScreenTopPadding = 12.dp
private val ScreenBottomPadding = 24.dp
private val SectionSpacing = 18.dp

@Composable
@Suppress("LongParameterList", "LongMethod")
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    hiddenTabs: List<NavItem>,
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickLibraryUpdateErrors: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickPlayerSettings: () -> Unit,
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val accentColor = LocalSettingsAccentColor.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val aiPreferences = remember { Injekt.get<AiPreferences>() }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
            ) {}
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        ScrollbarLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = ScreenTopPadding,
                bottom = ScreenBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            item(key = "more-logo-header") {
                LogoHeader()
            }

            item(key = "more-quick-modes") {
                MoreSection(
                    title = "Quick Modes",
                    accentColor = accentColor,
                ) {
                    MoreSwitchItem(
                        title = stringResource(MR.strings.label_downloaded_only),
                        subtitle = stringResource(MR.strings.downloaded_only_summary),
                        icon = Icons.Outlined.CloudOff,
                        iconTint = accentColor,
                        checked = downloadedOnly,
                        onCheckedChanged = onDownloadedOnlyChange,
                        showDivider = true,
                    )
                    MoreSwitchItem(
                        title = stringResource(MR.strings.pref_incognito_mode),
                        subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                        iconTint = accentColor,
                        checked = incognitoMode,
                        onCheckedChanged = onIncognitoModeChange,
                    )
                }
            }

            item(key = "more-library") {
                MoreSection(
                    title = "Library",
                    accentColor = accentColor,
                ) {
                    val downloadQueueState = downloadQueueStateProvider()
                    MoreItem(
                        title = stringResource(MR.strings.label_download_queue),
                        subtitle = when (downloadQueueState) {
                            DownloadQueueState.Stopped -> null
                            is DownloadQueueState.Paused -> {
                                val pending = downloadQueueState.pending
                                if (pending == 0) {
                                    stringResource(MR.strings.paused)
                                } else {
                                    "${stringResource(MR.strings.paused)} • ${
                                        pluralStringResource(
                                            MR.plurals.download_queue_summary,
                                            count = pending,
                                            pending,
                                        )
                                    }"
                                }
                            }
                            is DownloadQueueState.Downloading -> {
                                val pending = downloadQueueState.pending
                                pluralStringResource(
                                    MR.plurals.download_queue_summary,
                                    count = pending,
                                    pending,
                                )
                            }
                        },
                        icon = Icons.Outlined.GetApp,
                        iconTint = accentColor,
                        onClick = onClickDownloadQueue,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.general_categories),
                        icon = Icons.AutoMirrored.Outlined.Label,
                        iconTint = accentColor,
                        onClick = onClickCategories,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_stats),
                        icon = Icons.Outlined.QueryStats,
                        iconTint = accentColor,
                        onClick = onClickStats,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(SYMR.strings.option_label_library_update_errors),
                        icon = Icons.Outlined.ErrorOutline,
                        iconTint = accentColor,
                        onClick = onClickLibraryUpdateErrors,
                    )
                }
            }

            item(key = "more-system") {
                val enableAi by aiPreferences.enableAi().collectAsState()
                val enableAiAssistant by aiPreferences.enableAiAssistant().collectAsState()
                val showDiagnostics = enableAi && enableAiAssistant
                MoreSection(
                    title = "System",
                    accentColor = accentColor,
                ) {
                    MoreItem(
                        title = "Extension Health",
                        subtitle = "Real-time telemetry and source status",
                        icon = Icons.Outlined.MonitorHeart,
                        iconTint = accentColor,
                        onClick = { navigator.push(InfrastructureScreen) },
                        showDivider = showDiagnostics,
                    )
                    if (showDiagnostics) {
                        MoreItem(
                            title = "App Diagnostics",
                            subtitle = "Automated troubleshooting and AI insights",
                            icon = Icons.Default.Terminal,
                            iconTint = accentColor,
                            onClick = { navigator.push(AiAssistantScreen()) },
                        )
                    }
                }
            }

            item(key = "more-general") {
                MoreSection(
                    title = "General",
                    accentColor = accentColor,
                ) {
                    hiddenTabs.forEach { navItem ->
                        MoreItem(
                            title = stringResource(navItem.titleRes),
                            icon = navItem.unselectedIcon,
                            iconTint = accentColor,
                            onClick = {
                                scope.launch {
                                    val homeTab = when (navItem) {
                                        NavItem.LIBRARY -> HomeScreen.HomeTab.AnimeLib()
                                        NavItem.FEED -> HomeScreen.HomeTab.Feed
                                        NavItem.UPDATES -> HomeScreen.HomeTab.Updates
                                        NavItem.HISTORY -> HomeScreen.HomeTab.History
                                        NavItem.SCHEDULE -> HomeScreen.HomeTab.Schedule
                                        NavItem.BROWSE -> HomeScreen.HomeTab.Browse()
                                        NavItem.MORE -> HomeScreen.HomeTab.More(false)
                                    }
                                    HomeScreen.openTab(homeTab)
                                }
                            },
                            showDivider = true,
                        )
                    }
                    MoreItem(
                        title = stringResource(MR.strings.pref_bottom_nav_settings),
                        icon = Icons.Outlined.CallToAction,
                        iconTint = accentColor,
                        onClick = { navigator.push(NavigationSettingsScreen(null)) },
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_data_storage),
                        icon = Icons.Outlined.Storage,
                        iconTint = accentColor,
                        onClick = onClickDataAndStorage,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_settings),
                        icon = Icons.Outlined.Settings,
                        iconTint = accentColor,
                        onClick = onClickSettings,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_player_settings),
                        icon = Icons.Outlined.VideoSettings,
                        iconTint = accentColor,
                        onClick = onClickPlayerSettings,
                    )
                }
            }

            item(key = "more-support") {
                MoreSection(
                    title = "Support",
                    accentColor = accentColor,
                ) {
                    MoreItem(
                        title = stringResource(MR.strings.pref_category_about),
                        icon = Icons.Outlined.Info,
                        iconTint = accentColor,
                        onClick = onClickAbout,
                        showDivider = true,
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_help),
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        iconTint = accentColor,
                        onClick = { uriHandler.openUri(Constants.URL_HELP) },
                    )
                }
            }
        }
    }
}
