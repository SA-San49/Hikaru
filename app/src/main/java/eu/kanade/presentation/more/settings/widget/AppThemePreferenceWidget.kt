package eu.kanade.presentation.more.settings.widget

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.tvFocusHighlight
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

private const val PREVIEW_ASPECT_RATIO = 0.68f
private const val SELECTED_SCALE = 1.03f
private const val UNSELECTED_SCALE = 1f
private const val SCALE_ANIM_MS = 160
private const val FOCUSED_SCALE = 1.05f
private const val SWATCH_PILL_BG_ALPHA = 0.7f
private const val UNSELECTED_BORDER_ALPHA = 0.45f
private const val MINI_CARD_BORDER_ALPHA = 0.30f
private const val SKELETON_PRIMARY_ALPHA = 0.80f
private const val SKELETON_SECONDARY_ALPHA = 0.42f
private const val SWITCH_TRACK_ALPHA = 0.28f
private const val INACTIVE_NAV_DOT_ALPHA = 0.40f
private const val TITLE_FRACTION_WIDE = 0.85f
private const val TITLE_FRACTION_NARROW = 0.60f

private val CardItemWidth = 124.dp
private val ListSpacing = 12.dp
private val CardCornerRadius = 22.dp
private val InnerCornerRadius = 18.dp
private val SelectedBorderWidth = 2.5.dp
private val UnselectedBorderWidth = 1.dp
private val CardInnerPadding = 10.dp
private val CardSectionSpacing = 8.dp
private val SwatchDotSize = 10.dp
private val SwatchGap = 4.dp
private val SwatchPillPaddingH = 6.dp
private val SwatchPillPaddingV = 4.dp
private val CheckIconSize = 18.dp
private val MiniCardCorner = 12.dp
private val MiniCardPadding = 8.dp
private val MiniBadgeSize = 20.dp
private val MiniBadgeCorner = 6.dp
private val SkeletonBarHeight = 5.dp
private val SkeletonBarSpacing = 4.dp
private val MiniSwitchTrackWidth = 22.dp
private val MiniSwitchTrackHeight = 12.dp
private val MiniSwitchThumbSize = 8.dp
private val MiniSwitchTrackPadding = 2.dp
private val DockCorner = 14.dp
private val DockHeight = 24.dp
private val DockActivePillWidth = 24.dp
private val DockActivePillHeight = 14.dp
private val DockDotSize = 6.dp
private val LabelTopSpacing = 8.dp

@Composable
internal fun AppThemePreferenceWidget(
    value: AppTheme,
    amoled: Boolean,
    onItemClick: (AppTheme) -> Unit,
) {
    BasePreferenceWidget(
        subcomponent = {
            AppThemesList(
                currentTheme = value,
                amoled = amoled,
                onItemClick = onItemClick,
            )
        },
    )
}

@Composable
private fun AppThemesList(
    currentTheme: AppTheme,
    amoled: Boolean,
    onItemClick: (AppTheme) -> Unit,
) {
    val context = LocalContext.current
    val appThemes = remember {
        AppTheme.entries
            .filterNot { it.titleRes == null || (it == AppTheme.MONET && !DeviceUtil.isDynamicColorAvailable) }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = PrefsHorizontalPadding, vertical = CardSectionSpacing),
        horizontalArrangement = Arrangement.spacedBy(ListSpacing),
    ) {
        items(
            items = appThemes,
            key = { "theme-${it.name}" },
        ) { appTheme ->
            val isSelected = currentTheme == appTheme
            Column(
                modifier = Modifier.width(CardItemWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TachiyomiTheme(
                    appTheme = appTheme,
                    amoled = amoled,
                ) {
                    AppThemePreviewItem(
                        selected = isSelected,
                        onClick = {
                            onItemClick(appTheme)
                            (context as? Activity)?.let { ActivityCompat.recreate(it) }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(LabelTopSpacing))

                Text(
                    text = stringResource(appTheme.titleRes!!),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
fun AppThemePreviewItem(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else UNSELECTED_SCALE,
        animationSpec = tween(durationMillis = SCALE_ANIM_MS),
        label = "themeCardScale",
    )
    val outerShape = RoundedCornerShape(CardCornerRadius)
    val innerShape = RoundedCornerShape(InnerCornerRadius)

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusHighlight(
                shape = outerShape,
                focusedScale = FOCUSED_SCALE,
            )
            .fillMaxWidth()
            .aspectRatio(PREVIEW_ASPECT_RATIO)
            .border(
                width = if (selected) SelectedBorderWidth else UnselectedBorderWidth,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = UNSELECTED_BORDER_ALPHA)
                },
                shape = outerShape,
            )
            .padding(if (selected) SelectedBorderWidth else UnselectedBorderWidth)
            .clip(innerShape)
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(CardInnerPadding),
    ) {
        Column(
            modifier = Modifier.matchParentSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar: Trio palette swatches + Selected checkmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = SWATCH_PILL_BG_ALPHA))
                        .padding(horizontal = SwatchPillPaddingH, vertical = SwatchPillPaddingV),
                    horizontalArrangement = Arrangement.spacedBy(SwatchGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(SwatchDotSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Box(
                        modifier = Modifier
                            .size(SwatchDotSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                    Box(
                        modifier = Modifier
                            .size(SwatchDotSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }

                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(MR.strings.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(CheckIconSize),
                    )
                }
            }

            // Middle: Expressive M3 tinted card stack
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(MiniCardCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = UnselectedBorderWidth,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MINI_CARD_BORDER_ALPHA),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(MiniCardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CardSectionSpacing),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MiniBadgeSize)
                                .clip(RoundedCornerShape(MiniBadgeCorner))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(SwatchDotSize)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(SkeletonBarSpacing),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(TITLE_FRACTION_WIDE)
                                    .height(SkeletonBarHeight)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = SKELETON_PRIMARY_ALPHA),
                                    ),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(TITLE_FRACTION_NARROW)
                                    .height(SkeletonBarHeight)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SKELETON_SECONDARY_ALPHA),
                                    ),
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(MiniCardCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = UnselectedBorderWidth,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MINI_CARD_BORDER_ALPHA),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(MiniCardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = CardSectionSpacing)
                                .height(SkeletonBarHeight)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SKELETON_SECONDARY_ALPHA),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .size(width = MiniSwitchTrackWidth, height = MiniSwitchTrackHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = SWITCH_TRACK_ALPHA))
                                .padding(MiniSwitchTrackPadding),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MiniSwitchThumbSize)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }

            // Bottom: Modern floating M3 navigation dock preview
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DockHeight),
                shape = RoundedCornerShape(DockCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SwatchPillPaddingH),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = DockActivePillWidth, height = DockActivePillHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(DockDotSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSecondaryContainer),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(DockDotSize)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = INACTIVE_NAV_DOT_ALPHA),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .size(DockDotSize)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = INACTIVE_NAV_DOT_ALPHA),
                            ),
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AppThemesListPreview() {
    var appTheme by remember { mutableStateOf(AppTheme.DEFAULT) }
    Injekt.addSingleton(fullType<UiPreferences>(), UiPreferences(InMemoryPreferenceStore()))
    TachiyomiTheme(appTheme = appTheme) {
        Surface {
            AppThemesList(
                currentTheme = appTheme,
                amoled = false,
                onItemClick = { appTheme = it },
            )
        }
    }
}
