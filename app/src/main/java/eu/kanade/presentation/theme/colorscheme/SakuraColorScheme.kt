package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Sakura theme
 * Key colors: Sakura Pink #FF8FAB, Soft Rose #FFB3C6, Matcha #6EE7B7
 */
internal object SakuraColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFF8FAB),
        onPrimary = Color(0xFF3D061A),
        primaryContainer = Color(0xFF6B1E38),
        onPrimaryContainer = Color(0xFFFFDCE5),
        inversePrimary = Color(0xFFB53B5E),
        secondary = Color(0xFFFFB3C6),
        onSecondary = Color(0xFF3B0E1D),
        secondaryContainer = Color(0xFF562134),
        onSecondaryContainer = Color(0xFFFFE0E8),
        tertiary = Color(0xFF7BE0B2),
        onTertiary = Color(0xFF052E1D),
        tertiaryContainer = Color(0xFF165239),
        onTertiaryContainer = Color(0xFFC6F9DF),
        background = Color(0xFF191216),
        onBackground = Color(0xFFF3E7EC),
        surface = Color(0xFF191216),
        onSurface = Color(0xFFF3E7EC),
        surfaceVariant = Color(0xFF281C22),
        onSurfaceVariant = Color(0xFFD6BFC8),
        surfaceTint = Color(0xFFFF8FAB),
        inverseSurface = Color(0xFFF3E7EC),
        inverseOnSurface = Color(0xFF191216),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF9B808B),
        outlineVariant = Color(0xFF47333C),
        surfaceContainerLowest = Color(0xFF140D11),
        surfaceContainerLow = Color(0xFF21171C),
        surfaceContainer = Color(0xFF281C22),
        surfaceContainerHigh = Color(0xFF31222A),
        surfaceContainerHighest = Color(0xFF3B2933),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFB53B5E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9E3),
        onPrimaryContainer = Color(0xFF3E0018),
        inversePrimary = Color(0xFFFF8FAB),
        secondary = Color(0xFF984B63),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCE6),
        onSecondaryContainer = Color(0xFF38091B),
        tertiary = Color(0xFF1E7A55),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC2F7DE),
        onTertiaryContainer = Color(0xFF042919),
        background = Color(0xFFFFF8FA),
        onBackground = Color(0xFF22181C),
        surface = Color(0xFFFFF8FA),
        onSurface = Color(0xFF22181C),
        surfaceVariant = Color(0xFFFAEBEE),
        onSurfaceVariant = Color(0xFF564249),
        surfaceTint = Color(0xFFB53B5E),
        inverseSurface = Color(0xFF372B30),
        inverseOnSurface = Color(0xFFFCEFF2),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF887179),
        outlineVariant = Color(0xFFE0C8D0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFDF1F4),
        surfaceContainer = Color(0xFFFAEBEE),
        surfaceContainerHigh = Color(0xFFF5E1E6),
        surfaceContainerHighest = Color(0xFFEED7DD),
    )
}
