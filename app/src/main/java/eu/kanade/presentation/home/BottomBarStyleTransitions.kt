package eu.kanade.presentation.home

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import eu.kanade.domain.ui.model.BottomBarStyle
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut

private const val DEFAULT_FADE_MS = 200
private const val DURATION_GLASS_IN = 280
private const val DURATION_GLASS_OUT = 210
private const val DURATION_CAPSULE_IN = 220
private const val DURATION_CAPSULE_OUT = 180
private const val DURATION_BUBBLE_FADE = 220
private const val DURATION_ARC_IN = 290
private const val DURATION_ARC_OUT = 210
private const val DURATION_WAVE_IN = 330
private const val DURATION_WAVE_OUT = 240
private const val DURATION_PILL_IN = 260
private const val DURATION_PILL_OUT = 190
private const val DURATION_HORIZON_IN = 250
private const val DURATION_HORIZON_OUT = 200

private const val DIVISOR_THREE = 3
private const val DIVISOR_FOUR = 4
private const val DIVISOR_FIVE = 5
private const val DIVISOR_EIGHT = 8
private const val DIVISOR_TEN = 10
private const val DIVISOR_TWELVE = 12
private const val FLOAT_WAVE_IN = 0.65f
private const val FLOAT_WAVE_OUT = 0.35f
private const val FLOAT_PILL_IN = 0.42f
private const val FLOAT_PILL_OUT = 0.25f

private const val SCALE_GLASS_IN = 0.96f
private const val SCALE_BUBBLE_IN = 0.92f
private const val SCALE_BUBBLE_OUT = 0.96f
private const val SCALE_PILL_IN = 0.94f
private const val SCALE_PILL_OUT = 1.03f

fun bottomBarTabTransition(
    style: BottomBarStyle,
    direction: Int,
    animatedTransitions: Boolean,
): ContentTransform {
    if (!animatedTransitions) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    val dir = if (direction >= 0) 1 else -1
    return when (style) {
        BottomBarStyle.DEFAULT -> {
            materialFadeThroughIn(durationMillis = DEFAULT_FADE_MS) togetherWith
                materialFadeThroughOut(durationMillis = DEFAULT_FADE_MS)
        }
        BottomBarStyle.GLASS_DOCK -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_GLASS_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { (it / DIVISOR_THREE) * dir },
            ) + scaleIn(
                initialScale = SCALE_GLASS_IN,
                animationSpec = tween(durationMillis = DURATION_GLASS_IN, easing = LinearOutSlowInEasing),
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_GLASS_IN),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_GLASS_OUT, easing = FastOutLinearInEasing),
                targetOffsetX = { -(it / DIVISOR_FIVE) * dir },
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_GLASS_OUT)))
        }
        BottomBarStyle.CAPSULE_BAR -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_CAPSULE_IN, easing = LinearOutSlowInEasing),
                initialOffsetX = { (it / 2) * dir },
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_CAPSULE_IN),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_CAPSULE_OUT, easing = FastOutLinearInEasing),
                targetOffsetX = { -(it / 2) * dir },
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_CAPSULE_OUT)))
        }
        BottomBarStyle.ELEVATED_BUBBLE -> {
            (slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialOffsetX = { (it / DIVISOR_FOUR) * dir },
            ) + slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it / DIVISOR_EIGHT },
            ) + scaleIn(
                initialScale = SCALE_BUBBLE_IN,
                animationSpec = tween(durationMillis = DURATION_BUBBLE_FADE),
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_BUBBLE_FADE),
            )) togetherWith (slideOutVertically(
                animationSpec = tween(durationMillis = DURATION_CAPSULE_OUT),
                targetOffsetY = { it / DIVISOR_TWELVE },
            ) + scaleOut(
                targetScale = SCALE_BUBBLE_OUT,
                animationSpec = tween(durationMillis = DURATION_CAPSULE_OUT),
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_CAPSULE_OUT)))
        }
        BottomBarStyle.FLOATING_CENTER -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_ARC_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { (it * FLOAT_PILL_IN * dir).toInt() },
            ) + slideInVertically(
                animationSpec = tween(durationMillis = DURATION_ARC_IN, easing = LinearOutSlowInEasing),
                initialOffsetY = { -(it / DIVISOR_TEN) },
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_ARC_IN),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_ARC_OUT),
                targetOffsetX = { -(it * FLOAT_PILL_OUT * dir).toInt() },
            ) + slideOutVertically(
                animationSpec = tween(durationMillis = DURATION_ARC_OUT),
                targetOffsetY = { it / DIVISOR_TEN },
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_ARC_OUT)))
        }
        BottomBarStyle.WAVE_DOCK -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_WAVE_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { (it * FLOAT_WAVE_IN * dir).toInt() },
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_WAVE_IN, easing = LinearOutSlowInEasing),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_WAVE_OUT, easing = FastOutSlowInEasing),
                targetOffsetX = { -(it * FLOAT_WAVE_OUT * dir).toInt() },
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_WAVE_OUT)))
        }
        BottomBarStyle.FLOATING_PILL -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_PILL_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { (it * FLOAT_PILL_IN * dir).toInt() },
            ) + scaleIn(
                initialScale = SCALE_PILL_IN,
                animationSpec = tween(durationMillis = DURATION_PILL_IN, easing = LinearOutSlowInEasing),
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_PILL_IN),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_PILL_OUT),
                targetOffsetX = { -(it * FLOAT_PILL_OUT * dir).toInt() },
            ) + scaleOut(
                targetScale = SCALE_PILL_OUT,
                animationSpec = tween(durationMillis = DURATION_PILL_OUT),
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_PILL_OUT)))
        }
        BottomBarStyle.CENTER_DOCK -> {
            (slideInHorizontally(
                animationSpec = tween(durationMillis = DURATION_HORIZON_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { it * dir },
            ) + fadeIn(
                animationSpec = tween(durationMillis = DURATION_HORIZON_IN),
            )) togetherWith (slideOutHorizontally(
                animationSpec = tween(durationMillis = DURATION_HORIZON_OUT, easing = FastOutLinearInEasing),
                targetOffsetX = { -(it / 2) * dir },
            ) + fadeOut(animationSpec = tween(durationMillis = DURATION_HORIZON_OUT)))
        }
    }
}
