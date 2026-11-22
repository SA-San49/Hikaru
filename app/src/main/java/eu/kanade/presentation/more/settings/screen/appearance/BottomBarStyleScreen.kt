package eu.kanade.presentation.more.settings.screen.appearance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.BottomBarStyle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.home.StyledDockItem
import eu.kanade.presentation.home.StyledDockPreviewBar
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val INITIAL_PREVIEW_TAB = 2
private const val INDICATOR_ANIM_MS = 220
private const val CARD_SELECTED_BG_ALPHA = 0.14f
private const val CARD_BORDER_ACTIVE_ALPHA = 0.65f
private const val CARD_BORDER_IDLE_ALPHA = 0.25f
private const val CHIP_BG_ALPHA = 0.16f
private const val TRAY_BG_ALPHA = 0.55f
private const val TRAY_BORDER_ALPHA = 0.22f
private const val DOT_INACTIVE_ALPHA = 0.28f
private const val MIN_SUMMARY_LINES = 2

private val PagerHorizontalPad = 20.dp
private val PagerPageSpacing = 14.dp
private val CardCorner = 26.dp
private val CardPadding = 20.dp
private val CardInnerGap = 14.dp
private val TrayCorner = 22.dp
private val TrayPadH = 10.dp
private val TrayPadV = 14.dp
private val PreviewIconSize = 16.dp
private val CheckSize = 24.dp
private val CheckIconSize = 14.dp
private val BadgeCorner = 8.dp
private val BadgePadH = 8.dp
private val BadgePadV = 4.dp
private val ActivePillCorner = 6.dp
private val ActivePillPadH = 7.dp
private val ActivePillPadV = 3.dp
private val BadgeGap = 8.dp
private val HeaderCounterPadEnd = 16.dp
private val HeaderCounterPadH = 10.dp
private val HeaderCounterPadV = 4.dp
private val IndicatorRowGapTop = 20.dp
private val IndicatorDotGap = 6.dp
private val IndicatorDotHeight = 8.dp
private val IndicatorDotActiveWidth = 24.dp
private val IndicatorDotIdleWidth = 8.dp
private val HintTopGap = 10.dp
private val DockBarElevation = 6.dp
private val ApplyPadH = 16.dp
private val ApplyPadV = 12.dp
private val ApplyButtonHeight = 50.dp
private val ApplyCorner = 16.dp
private val ApplyIconSize = 18.dp
private val ButtonContentGap = 8.dp
private val SmallBadgeFont = 9.5.sp
private val BadgeLetterSpacing = 0.8.sp

private data class PreviewTabSpec(
    val id: String,
    val title: String,
    val icon: ImageVector,
)

private val PreviewTabs: List<PreviewTabSpec> = listOf(
    PreviewTabSpec("library", "Library", Icons.Outlined.CollectionsBookmark),
    PreviewTabSpec("history", "History", Icons.Outlined.History),
    PreviewTabSpec("schedule", "Schedule", Icons.Outlined.CalendarMonth),
    PreviewTabSpec("browse", "Browse", Icons.Outlined.Explore),
    PreviewTabSpec("more", "More", Icons.Outlined.MoreHoriz),
)

class BottomBarStyleScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val stylePref = remember { uiPreferences.bottomBarStyle() }
        val scope = rememberCoroutineScope()

        val styles = remember { BottomBarStyle.entries }
        var activeStyle by remember { mutableStateOf(stylePref.get()) }
        var selectedStyle by remember { mutableStateOf(activeStyle) }
        var previewTabIndex by remember { mutableIntStateOf(INITIAL_PREVIEW_TAB) }

        val initialPage = remember { styles.indexOf(activeStyle).coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = initialPage) { styles.size }
        val accentColor = MaterialTheme.colorScheme.primary

        LaunchedEffect(pagerState.currentPage) {
            selectedStyle = styles[pagerState.currentPage]
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Bottom bar style",
                    navigateUp = navigator::pop,
                    actions = {
                        Box(
                            modifier = Modifier
                                .padding(end = HeaderCounterPadEnd)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                                .padding(horizontal = HeaderCounterPadH, vertical = HeaderCounterPadV),
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${styles.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                DockApplyBottomBar(
                    selectedStyle = selectedStyle,
                    activeStyle = activeStyle,
                    accentColor = accentColor,
                    onApply = {
                        stylePref.set(selectedStyle)
                        activeStyle = selectedStyle
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = PagerHorizontalPad),
                    pageSpacing = PagerPageSpacing,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val style = styles[page]
                    DockSlideCard(
                        style = style,
                        isSelected = style == selectedStyle,
                        isActive = style == activeStyle,
                        previewTabIndex = previewTabIndex,
                        onSelectPreviewTab = { previewTabIndex = it },
                        accentColor = accentColor,
                    )
                }

                Spacer(modifier = Modifier.height(IndicatorRowGapTop))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(IndicatorDotGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    styles.forEachIndexed { idx, _ ->
                        val isCurrent = idx == pagerState.currentPage
                        val dotWidth by animateDpAsState(
                            targetValue = if (isCurrent) IndicatorDotActiveWidth else IndicatorDotIdleWidth,
                            animationSpec = tween(durationMillis = INDICATOR_ANIM_MS),
                            label = "pagerDotWidth",
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isCurrent) {
                                accentColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DOT_INACTIVE_ALPHA)
                            },
                            animationSpec = tween(durationMillis = INDICATOR_ANIM_MS),
                            label = "pagerDotColor",
                        )
                        Box(
                            modifier = Modifier
                                .height(IndicatorDotHeight)
                                .width(dotWidth)
                                .clip(CircleShape)
                                .background(dotColor)
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(HintTopGap))

                Text(
                    text = "Swipe to switch styles • Tap dock tabs to preview animation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DockSlideCard(
    style: BottomBarStyle,
    isSelected: Boolean,
    isActive: Boolean,
    previewTabIndex: Int,
    onSelectPreviewTab: (Int) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(CardCorner)
    val trayShape = RoundedCornerShape(TrayCorner)
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceLowest = MaterialTheme.colorScheme.surfaceContainerLowest

    val previewItems: ImmutableList<StyledDockItem> = remember(onSelectPreviewTab) {
        PreviewTabs.mapIndexed { idx, spec ->
            StyledDockItem(
                id = spec.id,
                title = spec.title,
                onClick = { onSelectPreviewTab(idx) },
                iconContent = { _, tint ->
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = spec.title,
                        tint = tint,
                        modifier = Modifier.size(PreviewIconSize),
                    )
                },
            )
        }.toImmutableList()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isSelected) {
                        listOf(
                            accentColor.copy(alpha = CARD_SELECTED_BG_ALPHA),
                            surfaceHigh,
                            surfaceLow,
                        )
                    } else {
                        listOf(surfaceHigh, surfaceLow)
                    },
                ),
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) {
                    accentColor.copy(alpha = CARD_BORDER_ACTIVE_ALPHA)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_IDLE_ALPHA)
                },
                shape = cardShape,
            )
            .padding(CardPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CardInnerGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BadgeGap),
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(ActivePillCorner))
                                .background(accentColor)
                                .padding(horizontal = ActivePillPadH, vertical = ActivePillPadV),
                        ) {
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = SmallBadgeFont,
                                letterSpacing = BadgeLetterSpacing,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(BadgeCorner))
                            .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                            .padding(horizontal = BadgePadH, vertical = BadgePadV),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ActivePillPadV),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Animation,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(CheckIconSize),
                        )
                        Text(
                            text = style.animationName,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(CheckSize)
                            .clip(CircleShape)
                            .background(accentColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(CheckIconSize),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(ActivePillPadV)) {
                Text(
                    text = style.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = style.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = MIN_SUMMARY_LINES,
                    maxLines = MIN_SUMMARY_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(trayShape)
                    .background(surfaceLowest.copy(alpha = TRAY_BG_ALPHA))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TRAY_BORDER_ALPHA),
                        shape = trayShape,
                    )
                    .padding(horizontal = TrayPadH, vertical = TrayPadV),
                contentAlignment = Alignment.Center,
            ) {
                StyledDockPreviewBar(
                    style = style,
                    items = previewItems,
                    selectedIndex = previewTabIndex,
                    compact = false,
                )
            }
        }
    }
}

@Composable
private fun DockApplyBottomBar(
    selectedStyle: BottomBarStyle,
    activeStyle: BottomBarStyle,
    accentColor: Color,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPendingChange = selectedStyle != activeStyle
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = DockBarElevation,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ApplyPadH, vertical = ApplyPadV),
        ) {
            Button(
                onClick = onApply,
                enabled = hasPendingChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ApplyButtonHeight),
                shape = RoundedCornerShape(ApplyCorner),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = if (hasPendingChange) {
                        Icons.Outlined.AutoAwesome
                    } else {
                        Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(ApplyIconSize),
                )
                Spacer(modifier = Modifier.width(ButtonContentGap))
                Text(
                    text = if (hasPendingChange) {
                        "Apply ${selectedStyle.title}"
                    } else {
                        "${activeStyle.title} Active"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
