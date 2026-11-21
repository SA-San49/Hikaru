package eu.kanade.presentation.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.components.ExpressiveBorderWidth
import eu.kanade.presentation.more.components.ExpressiveStatusPill
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.theme.SlateGrayAccent
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private const val GLASS_BASE_DARK_ALPHA = 0.16f
private const val GLASS_BASE_LIGHT_ALPHA = 0.12f
private const val GLASS_HIGHLIGHT_DARK_ALPHA = 0.28f
private const val GLASS_HIGHLIGHT_LIGHT_ALPHA = 0.22f
private const val GLASS_SHEEN_DARK_ALPHA = 0.08f
private const val GLASS_SHEEN_LIGHT_ALPHA = 0.30f
private const val GLASS_BORDER_ALPHA = 0.45f
private const val LOGO_CONTAINER_ALPHA = 0.26f
private const val LOGO_BORDER_ALPHA = 0.48f
private const val ABOUT_PILL_BG_ALPHA = 0.20f
private const val ABOUT_PILL_BORDER_ALPHA = 0.42f

private val HeroMinHeight = 236.dp
private val HeroCornerRadius = 28.dp
private val HeroPaddingHorizontal = 24.dp
private val HeroPaddingVertical = 26.dp
private val HeroContentGap = 14.dp
private val LogoBadgeSize = 86.dp
private val LogoBadgeRadius = 24.dp
private val LogoImageSize = 68.dp
private val TextVerticalGap = 8.dp

@Composable
fun LogoHeader(
    modifier: Modifier = Modifier,
) {
    val versionText = remember { AboutScreen.getVersionName(withBuildDate = false) }
    val heroShape = RoundedCornerShape(HeroCornerRadius)
    val logoShape = RoundedCornerShape(LogoBadgeRadius)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    val smallAboutAccent = LocalSettingsAccentColor.current

    val glassBaseColor = SlateGrayAccent.copy(
        alpha = if (isDark) GLASS_BASE_DARK_ALPHA else GLASS_BASE_LIGHT_ALPHA,
    )
    val glassGradientTop = SlateGrayAccent.copy(
        alpha = if (isDark) GLASS_HIGHLIGHT_DARK_ALPHA else GLASS_HIGHLIGHT_LIGHT_ALPHA,
    )
    val glassSheen = Color.White.copy(
        alpha = if (isDark) GLASS_SHEEN_DARK_ALPHA else GLASS_SHEEN_LIGHT_ALPHA,
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = heroShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = ExpressiveBorderWidth,
            color = SlateGrayAccent.copy(alpha = GLASS_BORDER_ALPHA),
        ),
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = HeroMinHeight)
                .background(glassBaseColor)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            glassSheen,
                            glassGradientTop,
                            glassBaseColor,
                        ),
                    ),
                )
                .padding(horizontal = HeroPaddingHorizontal, vertical = HeroPaddingVertical),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HeroContentGap),
            ) {
                Box(
                    modifier = Modifier
                        .size(LogoBadgeSize)
                        .clip(logoShape)
                        .background(SlateGrayAccent.copy(alpha = LOGO_CONTAINER_ALPHA))
                        .border(
                            width = ExpressiveBorderWidth,
                            color = SlateGrayAccent.copy(alpha = LOGO_BORDER_ALPHA),
                            shape = logoShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_splash_logo),
                        contentDescription = null,
                        modifier = Modifier.size(LogoImageSize),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TextVerticalGap),
                ) {
                    Text(
                        text = stringResource(MR.strings.app_name),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ExpressiveStatusPill(
                        text = versionText,
                        containerColor = smallAboutAccent.copy(alpha = ABOUT_PILL_BG_ALPHA),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        borderColor = smallAboutAccent.copy(alpha = ABOUT_PILL_BORDER_ALPHA),
                    )
                }
            }
        }
    }
}
