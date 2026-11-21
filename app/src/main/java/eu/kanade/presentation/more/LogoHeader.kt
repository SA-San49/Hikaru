package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.components.ExpressiveCardCornerRadius
import eu.kanade.presentation.more.components.ExpressiveStatusPill
import eu.kanade.presentation.more.components.expressiveCardBorder
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.theme.LocalIsDefaultTheme
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private const val THEME_WASH_START_ALPHA = 0.24f
private const val THEME_WASH_MID_ALPHA = 0.08f
private const val DEFAULT_WASH_DARK_ALPHA = 0.18f
private const val DEFAULT_WASH_LIGHT_ALPHA = 0.04f
private const val GLASS_SHEEN_DARK_ALPHA = 0.05f
private const val GLASS_SHEEN_LIGHT_ALPHA = 0.24f
private const val ABOUT_PILL_BG_ALPHA = 0.20f
private const val ABOUT_PILL_BORDER_ALPHA = 0.42f

private val HeroMinHeight = 236.dp
private val HeroPaddingHorizontal = 24.dp
private val HeroPaddingVertical = 26.dp
private val HeroContentGap = 14.dp
private val LogoImageSize = 76.dp
private val TextVerticalGap = 8.dp

@Composable
fun LogoHeader(
    modifier: Modifier = Modifier,
) {
    val versionText = remember { AboutScreen.getVersionName(withBuildDate = false) }
    val heroShape = RoundedCornerShape(ExpressiveCardCornerRadius)
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    val isDefaultTheme = LocalIsDefaultTheme.current
    val smallAboutAccent = LocalSettingsAccentColor.current
    val themeColor = MaterialTheme.colorScheme.primary

    val horizontalWash = remember(isDefaultTheme, isDark, themeColor) {
        if (isDefaultTheme) {
            Brush.horizontalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = if (isDark) DEFAULT_WASH_DARK_ALPHA else DEFAULT_WASH_LIGHT_ALPHA),
                    Color.Transparent,
                ),
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(
                    themeColor.copy(alpha = THEME_WASH_START_ALPHA),
                    themeColor.copy(alpha = THEME_WASH_MID_ALPHA),
                    Color.Transparent,
                ),
            )
        }
    }

    val glassSheen = remember(isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDark) GLASS_SHEEN_DARK_ALPHA else GLASS_SHEEN_LIGHT_ALPHA),
                Color.Transparent,
            ),
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = heroShape,
        color = surfaceLow,
        border = expressiveCardBorder(),
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = HeroMinHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(horizontalWash),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(glassSheen),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HeroPaddingHorizontal, vertical = HeroPaddingVertical),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HeroContentGap),
            ) {
                Image(
                    painter = painterResource(
                        if (isDark) R.drawable.ic_splash_logo_raw_dark else R.drawable.ic_splash_logo_raw,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(LogoImageSize),
                )
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
