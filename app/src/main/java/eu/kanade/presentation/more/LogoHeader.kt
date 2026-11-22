package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private val LogoVerticalPadding = 32.dp
private val LogoSize = 100.dp

@Composable
fun LogoHeader(
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LogoVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(
                if (isDark) R.drawable.ic_splash_logo_raw_dark else R.drawable.ic_splash_logo_raw,
            ),
            contentDescription = null,
            modifier = Modifier.size(LogoSize),
        )
    }
}
