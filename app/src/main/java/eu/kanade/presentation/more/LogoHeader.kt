package eu.kanade.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.more.components.ExpressiveStatusPill
import eu.kanade.presentation.more.components.expressiveCardBorder
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val HERO_TINT_ALPHA = 0.34f
private const val LOGO_CONTAINER_ALPHA = 0.22f
private const val LOGO_BORDER_ALPHA = 0.32f

private val HeroCornerRadius = 28.dp
private val HeroPaddingHorizontal = 20.dp
private val HeroPaddingVertical = 18.dp
private val HeroContentGap = 16.dp
private val LogoBadgeSize = 68.dp
private val LogoBadgeRadius = 20.dp
private val LogoImageSize = 54.dp
private val TextVerticalGap = 6.dp

@Composable
fun LogoHeader(
    modifier: Modifier = Modifier,
) {
    val versionText = remember { AboutScreen.getVersionName(withBuildDate = false) }
    val heroShape = RoundedCornerShape(HeroCornerRadius)
    val logoShape = RoundedCornerShape(LogoBadgeRadius)
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = heroShape,
        color = surfaceLow,
        border = expressiveCardBorder(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primaryContainer.copy(alpha = HERO_TINT_ALPHA),
                            surfaceLow,
                        ),
                    ),
                )
                .padding(horizontal = HeroPaddingHorizontal, vertical = HeroPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HeroContentGap),
        ) {
            Box(
                modifier = Modifier
                    .size(LogoBadgeSize)
                    .clip(logoShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = LOGO_CONTAINER_ALPHA))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = LOGO_BORDER_ALPHA),
                        shape = logoShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = R.drawable.ic_splash_logo,
                    contentDescription = null,
                    modifier = Modifier.size(LogoImageSize),
                )
            }
            Column(
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
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
