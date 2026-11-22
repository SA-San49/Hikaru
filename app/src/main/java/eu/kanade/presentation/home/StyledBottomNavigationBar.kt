package eu.kanade.presentation.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.model.BottomBarStyle
import eu.kanade.domain.ui.model.NavLabelVisibility
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

private const val SLIDE_DURATION_DEFAULT = 240
private const val SLIDE_DURATION_WAVE = 320
private const val SLIDE_DURATION_CAPSULE = 190
private const val COLOR_ANIM_DURATION = 180

private const val ALPHA_GLASS_SURFACE = 0.84f
private const val ALPHA_CAPSULE_SURFACE = 0.96f
private const val ALPHA_DARK_RAIL = 0.92f
private const val ALPHA_GLASS_BORDER_TOP = 0.30f
private const val ALPHA_GLASS_BORDER_MID = 0.14f
private const val ALPHA_GLASS_BORDER_END = 0.38f
private const val ALPHA_GLASS_SHEEN = 0.12f
private const val ALPHA_HALO_STRONG = 0.38f
private const val ALPHA_HALO_SOFT = 0.18f
private const val ALPHA_HALO_GLOW = 0.45f
private const val ALPHA_OUTLINE_SUBTLE = 0.45f
private const val ALPHA_SEPARATOR = 0.26f
private const val ALPHA_UNSELECTED_CONTENT = 0.72f
private const val ALPHA_DOT_INACTIVE = 0.38f
private const val ALPHA_CLASSIC_BAR = 0.96f
private const val ALPHA_WAVE_FILL_TOP = 0.34f
private const val ALPHA_WAVE_FILL_BOT = 0.14f
private const val ALPHA_WAVE_STROKE = 0.70f

private const val HALF_FLOAT = 0.5f
private const val THREE_FLOAT = 3.0f
private const val PILL_CORNER_PERCENT = 50
private const val FULL_ANGLE = 180f
private const val RIGHT_ANGLE = 90f
private const val SEP_FADE_DIST = 1.2f
private const val EDGE_TAPER_WINDOW = 0.18f

private const val WAVE_CYCLES = 2.0f
private const val WAVE_STEPS = 48
private const val WAVE_END_CAP_RATIO = 0.38f
private const val WAVE_AMP_RATIO = 0.065f
private const val WAVE_PHASE_OFFSET = 0.6f
private const val WAVE_CREST_SIGMA = 0.55f
private const val WAVE_CREST_HEIGHT_RATIO = 0.14f
private const val WAVE_CREST_WIDTH_MULT = 1.12f
private const val WAVE_CREST_GLOW_SPREAD = 1.2f
private const val WAVE_FILL_TOP_RATIO = 0.10f
private const val WAVE_FILL_HEIGHT_RATIO = 0.80f

private const val NOTCH_SIGMA = 0.46f
private const val NOTCH_DEPTH_RATIO = 0.14f
private const val ORB_DIAMETER_RATIO = 0.80f
private const val ORB_VERTICAL_CENTER_BIAS = 0.35f
private const val BUBBLE_DIAMETER_RATIO = 0.88f
private const val PILL_MEDALLION_WIDTH_RATIO = 0.92f
private const val RING_RADIUS_RATIO = 0.44f
private const val TWO_PI = 2.0f * PI.toFloat()

private const val SCALE_SELECTED = 1.06f
private const val SCALE_POP_SELECTED = 1.10f
private const val SCALE_UNSELECTED = 1.0f

private val OuterHorizontalPad = 12.dp
private val OuterVerticalPad = 8.dp
private val FullBarHeight = 66.dp
private val CompactBarHeight = 56.dp
private val FullCornerRadius = 28.dp
private val CompactCornerRadius = 24.dp
private val IndicatorInsetFull = 5.dp
private val IndicatorInsetCompact = 4.dp
private val BubbleLiftFull = (-7).dp
private val BubbleLiftCompact = (-5).dp
private val TabBubbleLiftFull = (-6).dp
private val TabBubbleLiftCompact = (-4).dp
private val PillLiftFull = (-4).dp
private val PillLiftCompact = (-3).dp
private val PillTrimFull = 6.dp
private val PillTrimCompact = 4.dp
private val DividerPadding = 14.dp
private val ClassicPillWidth = 56.dp
private val ClassicPillHeight = 28.dp
private val ClassicCompactPillWidth = 44.dp
private val ClassicCompactPillHeight = 22.dp
private val ClassicPillTopFull = 8.dp
private val ClassicPillTopCompact = 6.dp
private val ClassicLabelGapFull = 4.dp
private val ClassicElevationFull = 3.dp
private val PillCornerRadius = 22.dp
private val CueDotActiveSize = 5.dp
private val CueDotInactiveSize = 3.dp
private val CueDotGap = 3.dp
private val RingStrokeWidth = 1.5.dp
private val CenterLineMargin = 14.dp
private val CenterLineGap = 6.dp
private val MinPillWidth = 24.dp
private val MinMedallionWidth = 32.dp
private val CompactFontSize = 8.5.sp
private val CompactClassicFontSize = 9.sp
private val FullDockFontSize = 10.sp
private val FullClassicFontSize = 11.sp

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (THREE_FLOAT - 2f * t)
}

private fun edgeWindow(u: Float): Float {
    return smoothstep(0f, EDGE_TAPER_WINDOW, u) * smoothstep(1f, 1f - EDGE_TAPER_WINDOW, u)
}

@Immutable
data class StyledDockItem(
    val id: String,
    val title: String,
    val badgeCount: Int = 0,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val onDoubleClick: (() -> Unit)? = null,
    val iconContent: @Composable (selected: Boolean, tint: Color) -> Unit,
)

@Composable
fun StyledHomeNavigationBar(
    style: BottomBarStyle,
    items: ImmutableList<StyledDockItem>,
    selectedIndex: Int,
    navLabelVisibility: NavLabelVisibility,
    animatedTransitions: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeSelectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = OuterHorizontalPad, vertical = OuterVerticalPad),
        contentAlignment = Alignment.Center,
    ) {
        StyledDockBarCore(
            style = style,
            items = items,
            selectedIndex = safeSelectedIndex,
            navLabelVisibility = navLabelVisibility,
            animatedTransitions = animatedTransitions,
            compact = false,
        )
    }
}

@Composable
fun StyledDockPreviewBar(
    style: BottomBarStyle,
    items: ImmutableList<StyledDockItem>,
    selectedIndex: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeSelectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        StyledDockBarCore(
            style = style,
            items = items,
            selectedIndex = safeSelectedIndex,
            navLabelVisibility = NavLabelVisibility.ALWAYS,
            animatedTransitions = true,
            compact = compact,
        )
    }
}

@Composable
private fun StyledDockBarCore(
    style: BottomBarStyle,
    items: ImmutableList<StyledDockItem>,
    selectedIndex: Int,
    navLabelVisibility: NavLabelVisibility,
    animatedTransitions: Boolean,
    compact: Boolean,
) {
    if (items.isEmpty()) return

    val barHeight = if (compact) CompactBarHeight else FullBarHeight
    val cornerRadius = if (compact) CompactCornerRadius else FullCornerRadius
    val animatedIndex by animateIndicatorIndex(
        style = style,
        targetIndex = selectedIndex,
        animatedTransitions = animatedTransitions,
    )

    if (style == BottomBarStyle.DEFAULT) {
        ClassicMaterialPreviewBar(
            items = items,
            selectedIndex = selectedIndex,
            animatedIndex = animatedIndex,
            barHeight = barHeight,
            compact = compact,
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight),
    ) {
        val count = items.size
        val slotWidth = maxWidth / count
        val barHeightPx = constraints.maxHeight.toFloat()

        DockBackdropLayer(
            style = style,
            slotCount = count,
            animatedIndex = animatedIndex,
            cornerRadius = cornerRadius,
        )

        DockSlidingIndicatorLayer(
            style = style,
            slotWidth = slotWidth,
            barHeight = barHeight,
            barHeightPx = barHeightPx,
            animatedIndex = animatedIndex,
            compact = compact,
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                DockTabSlot(
                    item = item,
                    style = style,
                    selected = isSelected,
                    navLabelVisibility = navLabelVisibility,
                    compact = compact,
                    modifier = item.modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun animateIndicatorIndex(
    style: BottomBarStyle,
    targetIndex: Int,
    animatedTransitions: Boolean,
): State<Float> {
    val target = targetIndex.toFloat()
    if (!animatedTransitions) {
        return remember(target) { mutableFloatStateOf(target) }
    }
    return when (style) {
        BottomBarStyle.ELEVATED_BUBBLE -> animateFloatAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "bubbleIndicatorSlide",
        )
        BottomBarStyle.WAVE_DOCK -> animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_WAVE,
                easing = FastOutSlowInEasing,
            ),
            label = "waveIndicatorSlide",
        )
        BottomBarStyle.CAPSULE_BAR -> animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_CAPSULE,
                easing = FastOutSlowInEasing,
            ),
            label = "capsuleIndicatorSlide",
        )
        else -> animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = SLIDE_DURATION_DEFAULT,
                easing = FastOutSlowInEasing,
            ),
            label = "dockIndicatorSlide",
        )
    }
}

@Composable
private fun ClassicMaterialPreviewBar(
    items: ImmutableList<StyledDockItem>,
    selectedIndex: Int,
    animatedIndex: Float,
    barHeight: Dp,
    compact: Boolean,
) {
    val pillW = if (compact) ClassicCompactPillWidth else ClassicPillWidth
    val pillH = if (compact) ClassicCompactPillHeight else ClassicPillHeight
    val pillTop = if (compact) ClassicPillTopCompact else ClassicPillTopFull
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight),
        shape = RoundedCornerShape(if (compact) CompactCornerRadius else 0.dp),
        color = NavigationBarDefaults.containerColor.copy(alpha = ALPHA_CLASSIC_BAR),
        tonalElevation = if (compact) 2.dp else ClassicElevationFull,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val slotWidth = maxWidth / items.size
            val pillX = (slotWidth * animatedIndex) + (slotWidth - pillW) / 2
            Box(
                modifier = Modifier
                    .offset { IntOffset(pillX.roundToPx(), pillTop.roundToPx()) }
                    .size(width = pillW, height = pillH)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { idx, item ->
                    val isSelected = idx == selectedIndex
                    val tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = item.modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = item.onClick,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        item.iconContent(isSelected, tint)
                        Spacer(modifier = Modifier.height(if (compact) 2.dp else ClassicLabelGapFull))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = if (compact) CompactClassicFontSize else FullClassicFontSize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockBackdropLayer(
    style: BottomBarStyle,
    slotCount: Int,
    animatedIndex: Float,
    cornerRadius: Dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(cornerRadius)
    val pillShape = RoundedCornerShape(PILL_CORNER_PERCENT)

    when (style) {
        BottomBarStyle.GLASS_DOCK -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                surfaceHigh.copy(alpha = ALPHA_GLASS_SURFACE),
                                surfaceLow.copy(alpha = ALPHA_DARK_RAIL),
                            ),
                        ),
                    )
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ALPHA_GLASS_SHEEN),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ALPHA_GLASS_BORDER_TOP),
                                outlineVariant.copy(alpha = ALPHA_GLASS_BORDER_MID),
                                primary.copy(alpha = ALPHA_GLASS_BORDER_END),
                            ),
                        ),
                        shape = shape,
                    ),
            )
        }
        BottomBarStyle.CAPSULE_BAR -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(pillShape)
                    .background(surfaceLow.copy(alpha = ALPHA_CAPSULE_SURFACE))
                    .border(
                        width = 1.dp,
                        color = outlineVariant.copy(alpha = ALPHA_OUTLINE_SUBTLE),
                        shape = pillShape,
                    ),
            )
        }
        BottomBarStyle.ELEVATED_BUBBLE -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(pillShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                surfaceHigh.copy(alpha = ALPHA_DARK_RAIL),
                                surfaceLow.copy(alpha = ALPHA_CAPSULE_SURFACE),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = outlineVariant.copy(alpha = ALPHA_OUTLINE_SUBTLE),
                        shape = pillShape,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = DividerPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(slotCount - 1) { sepIdx ->
                        Spacer(modifier = Modifier.weight(1f))
                        val dist = abs(animatedIndex - (sepIdx + HALF_FLOAT))
                        val alphaScale = (dist / SEP_FADE_DIST).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(outlineVariant.copy(alpha = ALPHA_SEPARATOR * alphaScale)),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        BottomBarStyle.FLOATING_CENTER -> {
            val path = remember { Path() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val slotW = w / slotCount
                val centerX = (animatedIndex + HALF_FLOAT) * slotW
                val notchBump = h * NOTCH_DEPTH_RATIO
                val radius = (h - notchBump) / 2f
                val safeSpan = (w - 2f * radius).coerceAtLeast(1f)
                path.reset()
                path.moveTo(radius, notchBump)
                for (i in 0..WAVE_STEPS) {
                    val u = i.toFloat() / WAVE_STEPS
                    val x = radius + safeSpan * u
                    val norm = (x - centerX) / (slotW * NOTCH_SIGMA)
                    val swell = exp((-norm * norm).toDouble()).toFloat() * notchBump * edgeWindow(u)
                    path.lineTo(x, notchBump - swell)
                }
                path.arcTo(
                    rect = Rect(w - 2f * radius, notchBump, w, h),
                    startAngleDegrees = -RIGHT_ANGLE,
                    sweepAngleDegrees = FULL_ANGLE,
                    forceMoveTo = false,
                )
                path.lineTo(radius, h)
                path.arcTo(
                    rect = Rect(0f, notchBump, 2f * radius, h),
                    startAngleDegrees = RIGHT_ANGLE,
                    sweepAngleDegrees = FULL_ANGLE,
                    forceMoveTo = false,
                )
                path.close()
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            surfaceHigh.copy(alpha = ALPHA_DARK_RAIL),
                            surfaceLow.copy(alpha = ALPHA_CAPSULE_SURFACE),
                        ),
                    ),
                )
                drawPath(
                    path = path,
                    color = outlineVariant.copy(alpha = ALPHA_OUTLINE_SUBTLE),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        BottomBarStyle.WAVE_DOCK -> {
            val wavePath = remember { Path() }
            val crestPath = remember { Path() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val slotW = w / slotCount
                val centerX = (animatedIndex + HALF_FLOAT) * slotW
                val endCapR = h * WAVE_END_CAP_RATIO
                val midY = h * HALF_FLOAT
                val baseTop = midY - endCapR
                val baseBot = midY + endCapR
                val safeSpan = (w - 2f * endCapR).coerceAtLeast(1f)
                val amp = h * WAVE_AMP_RATIO
                wavePath.reset()
                crestPath.reset()
                wavePath.moveTo(endCapR, baseTop)
                crestPath.moveTo(endCapR, baseTop)
                for (i in 0..WAVE_STEPS) {
                    val u = i.toFloat() / WAVE_STEPS
                    val x = endCapR + safeSpan * u
                    val taper = edgeWindow(u)
                    val waveY = sin(u * WAVE_CYCLES * TWO_PI) * amp * taper
                    val norm = (x - centerX) / (slotW * WAVE_CREST_SIGMA)
                    val swell = exp((-norm * norm).toDouble()).toFloat() * (h * WAVE_CREST_HEIGHT_RATIO) * taper
                    val y = (baseTop + waveY - swell).coerceAtLeast(1.dp.toPx())
                    wavePath.lineTo(x, y)
                    crestPath.lineTo(x, y)
                }
                wavePath.arcTo(
                    rect = Rect(w - 2f * endCapR, baseTop, w, baseBot),
                    startAngleDegrees = -RIGHT_ANGLE,
                    sweepAngleDegrees = FULL_ANGLE,
                    forceMoveTo = false,
                )
                for (i in WAVE_STEPS downTo 0) {
                    val u = i.toFloat() / WAVE_STEPS
                    val x = endCapR + safeSpan * u
                    val taper = edgeWindow(u)
                    val waveY = sin(u * WAVE_CYCLES * TWO_PI + WAVE_PHASE_OFFSET) * amp * taper
                    wavePath.lineTo(x, (baseBot + waveY).coerceAtMost(h - 1.dp.toPx()))
                }
                wavePath.arcTo(
                    rect = Rect(0f, baseTop, 2f * endCapR, baseBot),
                    startAngleDegrees = RIGHT_ANGLE,
                    sweepAngleDegrees = FULL_ANGLE,
                    forceMoveTo = false,
                )
                wavePath.close()
                drawPath(
                    path = wavePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            surfaceHigh.copy(alpha = ALPHA_DARK_RAIL),
                            surfaceLow.copy(alpha = ALPHA_CAPSULE_SURFACE),
                        ),
                    ),
                )
                drawPath(
                    path = wavePath,
                    color = outlineVariant.copy(alpha = ALPHA_OUTLINE_SUBTLE),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawPath(
                    path = crestPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primary.copy(alpha = ALPHA_WAVE_STROKE),
                            Color.Transparent,
                        ),
                        startX = centerX - slotW * WAVE_CREST_GLOW_SPREAD,
                        endX = centerX + slotW * WAVE_CREST_GLOW_SPREAD,
                    ),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        BottomBarStyle.FLOATING_PILL -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(pillShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                surfaceHigh.copy(alpha = ALPHA_GLASS_SURFACE),
                                surfaceLow.copy(alpha = ALPHA_CAPSULE_SURFACE),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = outlineVariant.copy(alpha = ALPHA_OUTLINE_SUBTLE),
                        shape = pillShape,
                    ),
            )
        }
        BottomBarStyle.CENTER_DOCK -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(surfaceLow.copy(alpha = ALPHA_DARK_RAIL))
                    .border(
                        width = 1.dp,
                        color = outlineVariant.copy(alpha = ALPHA_GLASS_BORDER_MID),
                        shape = shape,
                    ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val slotW = size.width / slotCount
                    val midY = size.height / 2f
                    val centerX = (animatedIndex + HALF_FLOAT) * slotW
                    val ringGap = size.height * RING_RADIUS_RATIO + CenterLineGap.toPx()
                    val strokeW = 1.dp.toPx()
                    val marginPx = CenterLineMargin.toPx()
                    if (centerX - ringGap > marginPx) {
                        drawLine(
                            color = primary.copy(alpha = ALPHA_HALO_STRONG),
                            start = Offset(marginPx, midY),
                            end = Offset(centerX - ringGap, midY),
                            strokeWidth = strokeW,
                        )
                    }
                    if (centerX + ringGap < size.width - marginPx) {
                        drawLine(
                            color = primary.copy(alpha = ALPHA_HALO_STRONG),
                            start = Offset(centerX + ringGap, midY),
                            end = Offset(size.width - marginPx, midY),
                            strokeWidth = strokeW,
                        )
                    }
                }
            }
        }
        BottomBarStyle.DEFAULT -> Unit
    }
}

@Composable
private fun DockSlidingIndicatorLayer(
    style: BottomBarStyle,
    slotWidth: Dp,
    barHeight: Dp,
    barHeightPx: Float,
    animatedIndex: Float,
    compact: Boolean,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val inset = if (compact) IndicatorInsetCompact else IndicatorInsetFull

    when (style) {
        BottomBarStyle.GLASS_DOCK -> {
            val orbSize = barHeight - inset * 2
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - orbSize) / 2
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), inset.roundToPx()) }
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary,
                                primary.copy(alpha = ALPHA_HALO_GLOW),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ALPHA_HALO_GLOW),
                                Color.Transparent,
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
        }
        BottomBarStyle.CAPSULE_BAR -> {
            val pillW = (slotWidth - inset * 2).coerceAtLeast(MinPillWidth)
            val pillH = barHeight - inset * 2
            val xOffset = (slotWidth * animatedIndex) + inset
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), inset.roundToPx()) }
                    .size(width = pillW, height = pillH)
                    .clip(RoundedCornerShape(PILL_CORNER_PERCENT))
                    .background(primary),
            )
        }
        BottomBarStyle.ELEVATED_BUBBLE -> {
            val diameter = barHeight * BUBBLE_DIAMETER_RATIO
            val lift = if (compact) BubbleLiftCompact else BubbleLiftFull
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - diameter) / 2
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), lift.roundToPx()) }
                    .size(diameter)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primary,
                                primary.copy(alpha = ALPHA_HALO_STRONG),
                            ),
                        ),
                    )
                    .border(
                        width = RingStrokeWidth,
                        color = Color.White.copy(alpha = ALPHA_HALO_STRONG),
                        shape = CircleShape,
                    ),
            )
        }
        BottomBarStyle.FLOATING_CENTER -> {
            val orbSize = barHeight * ORB_DIAMETER_RATIO
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - orbSize) / 2
            val yOffset = ((barHeight - orbSize) / 2) + (barHeight * NOTCH_DEPTH_RATIO * ORB_VERTICAL_CENTER_BIAS)
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), yOffset.roundToPx()) }
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = ALPHA_HALO_GLOW),
                                surfaceHigh,
                            ),
                        ),
                    )
                    .border(
                        width = RingStrokeWidth,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ALPHA_HALO_GLOW),
                                primary.copy(alpha = ALPHA_HALO_SOFT),
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
        }
        BottomBarStyle.WAVE_DOCK -> {
            val crestW = slotWidth * WAVE_CREST_WIDTH_MULT
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - crestW) / 2
            Canvas(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), 0) }
                    .size(width = crestW, height = barHeight),
            ) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primary.copy(alpha = ALPHA_WAVE_FILL_TOP),
                            primary.copy(alpha = ALPHA_WAVE_FILL_BOT),
                            Color.Transparent,
                        ),
                    ),
                    topLeft = Offset(0f, barHeightPx * WAVE_FILL_TOP_RATIO),
                    size = Size(size.width, size.height * WAVE_FILL_HEIGHT_RATIO),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                )
            }
        }
        BottomBarStyle.FLOATING_PILL -> {
            val pillW = (slotWidth * PILL_MEDALLION_WIDTH_RATIO).coerceAtLeast(MinMedallionWidth)
            val pillH = barHeight - (if (compact) PillTrimCompact else PillTrimFull)
            val lift = if (compact) PillLiftCompact else PillLiftFull
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - pillW) / 2
            val pillShape = RoundedCornerShape(PillCornerRadius)
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), lift.roundToPx()) }
                    .size(width = pillW, height = pillH)
                    .clip(pillShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primary.copy(alpha = ALPHA_HALO_GLOW),
                                surfaceHigh.copy(alpha = ALPHA_CAPSULE_SURFACE),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ALPHA_HALO_GLOW),
                                primary.copy(alpha = ALPHA_HALO_SOFT),
                            ),
                        ),
                        shape = pillShape,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Row(
                    modifier = Modifier.padding(bottom = if (compact) 2.dp else CueDotGap),
                    horizontalArrangement = Arrangement.spacedBy(CueDotGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(CueDotInactiveSize)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = ALPHA_DOT_INACTIVE)),
                    )
                    Box(
                        modifier = Modifier
                            .size(CueDotActiveSize)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                    Box(
                        modifier = Modifier
                            .size(CueDotInactiveSize)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = ALPHA_DOT_INACTIVE)),
                    )
                }
            }
        }
        BottomBarStyle.CENTER_DOCK -> {
            val ringSize = barHeight * (RING_RADIUS_RATIO * 2f)
            val xOffset = (slotWidth * animatedIndex) + (slotWidth - ringSize) / 2
            val yOffset = (barHeight - ringSize) / 2
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset.roundToPx(), yOffset.roundToPx()) }
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = ALPHA_HALO_SOFT),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .border(
                        width = RingStrokeWidth,
                        color = primary,
                        shape = CircleShape,
                    ),
            )
        }
        BottomBarStyle.DEFAULT -> Unit
    }
}

@Composable
private fun DockTabSlot(
    item: StyledDockItem,
    style: BottomBarStyle,
    selected: Boolean,
    navLabelVisibility: NavLabelVisibility,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val activeTint = when (style) {
        BottomBarStyle.GLASS_DOCK,
        BottomBarStyle.CAPSULE_BAR,
        BottomBarStyle.ELEVATED_BUBBLE,
        -> onPrimary
        BottomBarStyle.WAVE_DOCK,
        BottomBarStyle.CENTER_DOCK,
        -> primary
        BottomBarStyle.FLOATING_CENTER,
        BottomBarStyle.FLOATING_PILL,
        -> Color.White
        BottomBarStyle.DEFAULT -> onSurface
    }
    val targetTint = if (selected) activeTint else onSurface.copy(alpha = ALPHA_UNSELECTED_CONTENT)
    val contentTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(durationMillis = COLOR_ANIM_DURATION),
        label = "dockTabTint",
    )
    val scaleTarget = when {
        selected && (style == BottomBarStyle.ELEVATED_BUBBLE || style == BottomBarStyle.FLOATING_CENTER) ->
            SCALE_POP_SELECTED
        selected -> SCALE_SELECTED
        else -> SCALE_UNSELECTED
    }
    val itemScale by animateFloatAsState(
        targetValue = scaleTarget,
        animationSpec = tween(durationMillis = COLOR_ANIM_DURATION),
        label = "dockTabScale",
    )
    val verticalLiftTarget = when {
        selected && style == BottomBarStyle.ELEVATED_BUBBLE ->
            if (compact) TabBubbleLiftCompact else TabBubbleLiftFull
        selected && style == BottomBarStyle.FLOATING_PILL ->
            if (compact) PillLiftCompact else PillLiftFull
        selected && style == BottomBarStyle.FLOATING_CENTER -> 2.dp
        else -> 0.dp
    }
    val verticalLift by animateDpAsState(
        targetValue = verticalLiftTarget,
        animationSpec = tween(durationMillis = COLOR_ANIM_DURATION),
        label = "dockTabLift",
    )

    val showLabel = when (navLabelVisibility) {
        NavLabelVisibility.ALWAYS -> true
        NavLabelVisibility.SELECTED -> selected
        NavLabelVisibility.NEVER -> false
    }

    val clickModifier = if (item.onLongClick != null || item.onDoubleClick != null) {
        modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = item.onClick,
            onLongClick = item.onLongClick,
            onDoubleClick = item.onDoubleClick,
        )
    } else {
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = item.onClick,
        )
    }

    Column(
        modifier = clickModifier
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                translationY = verticalLift.toPx()
            }
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item.iconContent(selected, contentTint)
        if (showLabel) {
            Spacer(modifier = Modifier.height(if (compact) 1.dp else 2.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (compact) CompactFontSize else FullDockFontSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
