package eu.kanade.presentation.more.settings.screen.appearance

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.AppIconManager
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.tvFocusHighlight
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AppIconScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val appIconPref = remember { uiPreferences.appIcon() }

        var activeIcon by remember {
            val realActive = AppIconManager.getActiveAppIcon(context)
            if (appIconPref.get() != realActive) {
                appIconPref.set(realActive)
            }
            mutableStateOf(realActive)
        }
        var selectedIcon by remember { mutableStateOf(activeIcon) }
        var needsRestart by remember { mutableStateOf(false) }

        val animatedAccent by animateColorAsState(
            targetValue = Color(selectedIcon.accentHex),
            animationSpec = tween(durationMillis = 320),
            label = "appIconAccent",
        )

        val icons = remember { AppIcon.entries }
        val appLabel = androidStringResource(R.string.app_name)

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AMR.strings.pref_app_icon),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                AppIconBottomDock(
                    selectedIcon = selectedIcon,
                    activeIcon = activeIcon,
                    needsRestart = needsRestart,
                    accentColor = animatedAccent,
                    onApply = {
                        val applied = AppIconManager.setAppIcon(context, selectedIcon)
                        if (applied) {
                            appIconPref.set(selectedIcon)
                            activeIcon = selectedIcon
                            needsRestart = true
                            context.toast(MR.strings.requires_app_restart)
                        }
                    },
                    onRestart = {
                        (context as? Activity)?.finishAffinity()
                    },
                )
            },
        ) { contentPadding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(
                    key = "launcher-hero-stage",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    LauncherPreviewHeroStage(
                        selectedIcon = selectedIcon,
                        isApplied = selectedIcon == activeIcon,
                        accentColor = animatedAccent,
                        appLabel = appLabel,
                    )
                }

                item(
                    key = "section-header",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "ICON GALLERY",
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${icons.size} STYLES",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = animatedAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                items(
                    items = icons,
                    key = { "icon-card-${it.name}" },
                ) { icon ->
                    FuturisticAppIconCard(
                        icon = icon,
                        isSelected = icon == selectedIcon,
                        isActive = icon == activeIcon,
                        onClick = { selectedIcon = icon },
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherPreviewHeroStage(
    selectedIcon: AppIcon,
    isApplied: Boolean,
    accentColor: Color,
    appLabel: String,
) {
    val stageShape = RoundedCornerShape(28.dp)
    val stageBg = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(stageShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.22f),
                        stageBg,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.7f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        accentColor.copy(alpha = 0.35f),
                    ),
                ),
                shape = stageShape,
            )
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = if (isApplied) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Default.AutoAwesome
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (isApplied) {
                        "ACTIVE LAUNCHER ICON"
                    } else {
                        "LIVE LAUNCHER PREVIEW"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.1.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostLauncherTile()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.55f),
                                            accentColor.copy(alpha = 0.12f),
                                            Color.Transparent,
                                        ),
                                    ),
                                    shape = CircleShape,
                                ),
                        )
                        Image(
                            painter = painterResource(selectedIcon.iconResource),
                            contentDescription = selectedIcon.title,
                            modifier = Modifier
                                .size(78.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .border(
                                    width = 1.8.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            accentColor,
                                            Color.White.copy(alpha = 0.55f),
                                            accentColor.copy(alpha = 0.5f),
                                        ),
                                    ),
                                    shape = RoundedCornerShape(22.dp),
                                ),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = appLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                GhostLauncherTile()
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = selectedIcon.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${selectedIcon.styleTag} Edition • Updates home screen & app drawer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GhostLauncherTile() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(16.dp),
                ),
        )
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun FuturisticAppIconCard(
    icon: AppIcon,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val accent = remember(icon) { Color(icon.accentHex) }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "iconCardScale",
    )

    val cardShape = RoundedCornerShape(22.dp)
    val baseBg = MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .tvFocusHighlight(shape = cardShape, focusedScale = 1.04f)
            .clip(cardShape)
            .background(
                brush = if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.22f),
                            baseBg,
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            baseBg.copy(alpha = 0.78f),
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                },
            )
            .border(
                width = if (isSelected) 1.8.dp else 1.dp,
                brush = if (isSelected) {
                    Brush.linearGradient(
                        colors = listOf(
                            accent,
                            accent.copy(alpha = 0.35f),
                            accent.copy(alpha = 0.85f),
                        ),
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f),
                        ),
                    )
                },
                shape = cardShape,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(MR.strings.selected),
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(74.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        accent.copy(alpha = 0.45f),
                                        Color.Transparent,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                    )
                }
                Image(
                    painter = painterResource(icon.iconResource),
                    contentDescription = icon.title,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                accent.copy(alpha = 0.9f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(18.dp),
                        ),
                    contentScale = ContentScale.Crop,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = icon.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = icon.styleTag,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    accent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppIconBottomDock(
    selectedIcon: AppIcon,
    activeIcon: AppIcon,
    needsRestart: Boolean,
    accentColor: Color,
    onApply: () -> Unit,
    onRestart: () -> Unit,
) {
    val hasPendingChange = selectedIcon != activeIcon

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (needsRestart && !hasPendingChange) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.requires_app_restart),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onRestart,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Restart")
                    }
                }
            } else {
                Button(
                    onClick = onApply,
                    enabled = hasPendingChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.Black,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = if (hasPendingChange) {
                            Icons.Default.AutoAwesome
                        } else {
                            Icons.Filled.CheckCircle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasPendingChange) {
                            "Apply ${selectedIcon.title} Icon"
                        } else {
                            "${activeIcon.title} Icon Active"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
