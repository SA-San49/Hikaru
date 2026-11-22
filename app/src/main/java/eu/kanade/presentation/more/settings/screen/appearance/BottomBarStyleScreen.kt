package eu.kanade.presentation.more.settings.screen.appearance

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import eu.kanade.presentation.home.bottomBarTabTransition
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val STAGE_BG_ALPHA = 0.14f
private const val STAGE_BORDER_ALPHA = 0.45f
private const val STAGE_VIEWPORT_ALPHA = 0.60f
private const val CHIP_BG_ALPHA = 0.16f
private const val CARD_SELECTED_BG_ALPHA = 0.14f
private const val CARD_UNSELECTED_BORDER_ALPHA = 0.30f
private const val SKELETON_PRIMARY_ALPHA = 0.28f
private const val SKELETON_SECONDARY_ALPHA = 0.14f
private const val INITIAL_PREVIEW_TAB = 2

private val ListPadH = 16.dp
private val ListPadTop = 12.dp
private val ListPadBottom = 24.dp
private val ListItemGap = 12.dp
private val StageCorner = 24.dp
private val StagePadding = 16.dp
private val StageViewportCorner = 16.dp
private val StageViewportHeight = 112.dp
private val StageViewportPad = 14.dp
private val StageHeaderGap = 10.dp
private val StageDockGap = 12.dp
private val CardCorner = 20.dp
private val CardPadding = 14.dp
private val CardDockGap = 12.dp
private val CheckSize = 22.dp
private val CheckIconSize = 14.dp
private val PreviewIconSize = 16.dp
private val ChipCorner = 8.dp
private val ChipPadH = 8.dp
private val ChipPadV = 3.dp
private val PillCorner = 6.dp
private val PillPadH = 6.dp
private val PillPadV = 2.dp
private val DockBarElevation = 6.dp
private val ApplyPadH = 16.dp
private val ApplyPadV = 12.dp
private val ApplyButtonHeight = 50.dp
private val ApplyCorner = 16.dp
private val SkeletonBarWide = 120.dp
private val SkeletonBarNarrow = 84.dp
private val SkeletonBarHeight = 10.dp
private val SkeletonIconBox = 34.dp
private val SkeletonIconSize = 18.dp
private val SectionPadTop = 6.dp
private val SectionPadH = 4.dp
private val StageHeaderFontSize = 11.sp
private val BadgeFontSize = 9.sp
private val HeaderLetterSpacing = 1.4.sp
private val BadgeLetterSpacing = 0.8.sp
private val SectionSpacing = 8.dp
private val ApplyIconSize = 18.dp

private data class PreviewTabSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val PreviewTabs: List<PreviewTabSpec> = listOf(
    PreviewTabSpec("library", "Library", "Saved series & watchlists", Icons.Outlined.CollectionsBookmark),
    PreviewTabSpec("history", "History", "Recently watched episodes", Icons.Outlined.History),
    PreviewTabSpec("schedule", "Schedule", "Airing calendar & releases", Icons.Outlined.CalendarMonth),
    PreviewTabSpec("browse", "Browse", "Sources, feed & announcements", Icons.Outlined.Explore),
    PreviewTabSpec("more", "More", "Downloads, categories & settings", Icons.Outlined.MoreHoriz),
)

class BottomBarStyleScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val stylePref = remember { uiPreferences.bottomBarStyle() }

        var activeStyle by remember { mutableStateOf(stylePref.get()) }
        var selectedStyle by remember { mutableStateOf(activeStyle) }
        var previewTabIndex by remember { mutableIntStateOf(INITIAL_PREVIEW_TAB) }

        val styles = remember { BottomBarStyle.entries }
        val accentColor = MaterialTheme.colorScheme.primary

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Bottom navigation bar",
                    navigateUp = navigator::pop,
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = ListPadH,
                    end = ListPadH,
                    top = ListPadTop,
                    bottom = ListPadBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemGap),
            ) {
                item(key = "interactive-motion-stage") {
                    InteractiveDockMotionStage(
                        style = selectedStyle,
                        previewTabIndex = previewTabIndex,
                        onSelectPreviewTab = { previewTabIndex = it },
                        accentColor = accentColor,
                    )
                }

                item(key = "dock-gallery-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SectionPadTop, start = SectionPadH, end = SectionPadH),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "DOCK STYLES",
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = HeaderLetterSpacing,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${styles.size} ANIMATED STYLES",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = StageHeaderFontSize,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                items(
                    items = styles,
                    key = { "dock-style-${it.name}" },
                ) { style ->
                    DockStyleShowcaseCard(
                        style = style,
                        isSelected = style == selectedStyle,
                        isActive = style == activeStyle,
                        previewTabIndex = previewTabIndex,
                        accentColor = accentColor,
                        onClick = { selectedStyle = style },
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveDockMotionStage(
    style: BottomBarStyle,
    previewTabIndex: Int,
    onSelectPreviewTab: (Int) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val stageShape = RoundedCornerShape(StageCorner)
    val viewportShape = RoundedCornerShape(StageViewportCorner)
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLowest = MaterialTheme.colorScheme.surfaceContainerLowest

    val interactiveDockItems: ImmutableList<StyledDockItem> = remember(onSelectPreviewTab) {
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
            .clip(stageShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = STAGE_BG_ALPHA),
                        surfaceHigh,
                        surfaceLowest,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = STAGE_BORDER_ALPHA),
                shape = stageShape,
            )
            .padding(StagePadding),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(StageDockGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = style.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Tap tabs below to preview ${style.animationName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ChipCorner))
                        .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                        .padding(horizontal = ChipPadH, vertical = ChipPadV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SectionPadH),
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StageViewportHeight)
                    .clip(viewportShape)
                    .background(surfaceLowest.copy(alpha = STAGE_VIEWPORT_ALPHA))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_UNSELECTED_BORDER_ALPHA),
                        shape = viewportShape,
                    ),
            ) {
                AnimatedContent(
                    targetState = previewTabIndex,
                    transitionSpec = {
                        val direction = if (targetState >= initialState) 1 else -1
                        bottomBarTabTransition(
                            style = style,
                            direction = direction,
                            animatedTransitions = true,
                        )
                    },
                    label = "stageTabSlidePreview",
                    modifier = Modifier.fillMaxSize(),
                ) { tabIdx ->
                    val tabSpec = PreviewTabs[tabIdx.coerceIn(0, PreviewTabs.lastIndex)]
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(StageViewportPad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StageHeaderGap),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(SkeletonIconBox)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = CHIP_BG_ALPHA)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = tabSpec.icon,
                                contentDescription = tabSpec.title,
                                tint = accentColor,
                                modifier = Modifier.size(SkeletonIconSize),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(PillPadH),
                        ) {
                            Text(
                                text = tabSpec.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = tabSpec.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(PillPadV))
                            Row(horizontalArrangement = Arrangement.spacedBy(SectionSpacing)) {
                                Box(
                                    modifier = Modifier
                                        .size(width = SkeletonBarWide, height = SkeletonBarHeight)
                                        .clip(CircleShape)
                                        .background(accentColor.copy(alpha = SKELETON_PRIMARY_ALPHA)),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = SkeletonBarNarrow, height = SkeletonBarHeight)
                                        .clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = SKELETON_SECONDARY_ALPHA,
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            StyledDockPreviewBar(
                style = style,
                items = interactiveDockItems,
                selectedIndex = previewTabIndex,
                compact = false,
            )
        }
    }
}

@Composable
private fun DockStyleShowcaseCard(
    style: BottomBarStyle,
    isSelected: Boolean,
    isActive: Boolean,
    previewTabIndex: Int,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(CardCorner)
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

    val cardItems: ImmutableList<StyledDockItem> = remember(onClick) {
        PreviewTabs.map { spec ->
            StyledDockItem(
                id = spec.id,
                title = spec.title,
                onClick = onClick,
                iconContent = { _, tint ->
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
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
                brush = if (isSelected) {
                    Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = CARD_SELECTED_BG_ALPHA),
                            surfaceHigh,
                        ),
                    )
                } else {
                    Brush.linearGradient(colors = listOf(surfaceHigh, surfaceLow))
                },
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) {
                    accentColor
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_UNSELECTED_BORDER_ALPHA)
                },
                shape = cardShape,
            )
            .clickable(onClick = onClick)
            .padding(CardPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CardDockGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
                    ) {
                        Text(
                            text = style.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(PillCorner))
                                    .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                                    .padding(horizontal = PillPadH, vertical = PillPadV),
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = BadgeFontSize,
                                    letterSpacing = BadgeLetterSpacing,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                )
                            }
                        }
                    }
                    Text(
                        text = style.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ChipCorner))
                        .background(accentColor.copy(alpha = CHIP_BG_ALPHA))
                        .padding(horizontal = ChipPadH, vertical = ChipPadV),
                ) {
                    Text(
                        text = style.animationName,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Medium,
                    )
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

            StyledDockPreviewBar(
                style = style,
                items = cardItems,
                selectedIndex = previewTabIndex,
                compact = true,
            )
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
                Spacer(modifier = Modifier.width(SectionSpacing))
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
