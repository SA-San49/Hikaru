package eu.kanade.presentation.more.settings.screen.player.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactRadius
import eu.kanade.presentation.more.components.ExpressiveBadgeCompactSize
import eu.kanade.presentation.more.components.ExpressiveCompactIconSize
import eu.kanade.presentation.more.components.ExpressiveDividerHorizontalInset
import eu.kanade.presentation.more.components.ExpressiveIconBadge
import eu.kanade.presentation.more.components.ExpressiveItemDivider
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.player.LayoutRegion
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.parseButtons
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val LayoutScreenPadding = 16.dp
private val LayoutSectionSpacing = 20.dp
private val RegionHorizontalPadding = 16.dp
private val RegionVerticalPadding = 12.dp
private val RegionChipsGap = 8.dp
private val RegionHeaderBottomSpacing = 10.dp

object PlayerSettingsLayoutMainScreen : Screen() {

    private fun readResolve(): Any = PlayerSettingsLayoutMainScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_player_layout),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(LayoutScreenPadding),
                verticalArrangement = Arrangement.spacedBy(LayoutSectionSpacing),
            ) {
                item {
                    val landscapeAccent = LocalSettingsAccentColor.current
                    MoreSection(
                        title = "Landscape Controls",
                        accentColor = landscapeAccent,
                    ) {
                        RegionSummaryItem(
                            region = LayoutRegion.TopRight,
                            playerPreferences = playerPreferences,
                            accentColor = landscapeAccent,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.TopRight)) },
                        )
                        ExpressiveItemDivider(insetStart = ExpressiveDividerHorizontalInset)
                        RegionSummaryItem(
                            region = LayoutRegion.BottomLeft,
                            playerPreferences = playerPreferences,
                            accentColor = landscapeAccent,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.BottomLeft)) },
                        )
                        ExpressiveItemDivider(insetStart = ExpressiveDividerHorizontalInset)
                        RegionSummaryItem(
                            region = LayoutRegion.BottomRight,
                            playerPreferences = playerPreferences,
                            accentColor = landscapeAccent,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.BottomRight)) },
                        )
                    }
                }

                item {
                    val portraitAccent = LocalSettingsAccentColor.current
                    MoreSection(
                        title = "Portrait Controls",
                        accentColor = portraitAccent,
                    ) {
                        RegionSummaryItem(
                            region = LayoutRegion.Portrait,
                            playerPreferences = playerPreferences,
                            accentColor = portraitAccent,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.Portrait)) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RegionSummaryItem(
        region: LayoutRegion,
        playerPreferences: PlayerPreferences,
        accentColor: Color,
        onClick: () -> Unit,
    ) {
        val buttonsPref = remember(region) {
            when (region) {
                LayoutRegion.TopRight -> playerPreferences.topRightControls()
                LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls()
                LayoutRegion.BottomRight -> playerPreferences.bottomRightControls()
                LayoutRegion.Portrait -> playerPreferences.portraitBottomControls()
            }
        }
        val buttonsCsv by buttonsPref.collectAsState()
        val buttons = remember(buttonsCsv) { parseButtons(buttonsCsv) }
        val isCastEnabled by playerPreferences.enableCast().collectAsState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = RegionHorizontalPadding, vertical = RegionVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(RegionHeaderBottomSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(region.titleRes),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ExpressiveIconBadge(
                    icon = Icons.Outlined.Edit,
                    accentColor = accentColor,
                    size = ExpressiveBadgeCompactSize,
                    cornerRadius = ExpressiveBadgeCompactRadius,
                    iconSize = ExpressiveCompactIconSize,
                )
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RegionChipsGap),
                verticalArrangement = Arrangement.spacedBy(RegionChipsGap, Alignment.CenterVertically),
            ) {
                val visibleButtons = buttons.filter { it != PlayerButton.Cast || isCastEnabled }
                if (visibleButtons.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    visibleButtons.forEach { button ->
                        PlayerButtonChip(
                            button = button,
                            enabled = true,
                            onClick = null,
                            badgeIcon = null,
                            badgeColor = null,
                        )
                    }
                }
            }
        }
    }
}
