package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Aurora theme
 * Key colors: Boreal Mint #2EE59D, Aurora Violet #A78BFA, Glacial Sky #38BDF8
 */
internal object AuroraColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF2EE59D),
        onPrimary = Color(0xFF002B19),
        primaryContainer = Color(0xFF0B4F35),
        onPrimaryContainer = Color(0xFFB3FCE0),
        inversePrimary = Color(0xFF008052),
        secondary = Color(0xFFA78BFA),
        onSecondary = Color(0xFF1D0D4A),
        secondaryContainer = Color(0xFF2D2359),
        onSecondaryContainer = Color(0xFFE6DCFF),
        tertiary = Color(0xFF38BDF8),
        onTertiary = Color(0xFF002538),
        tertiaryContainer = Color(0xFF0D4463),
        onTertiaryContainer = Color(0xFFC9EEFF),
        background = Color(0xFF0E161B),
        onBackground = Color(0xFFE2ECF1),
        surface = Color(0xFF0E161B),
        onSurface = Color(0xFFE2ECF1),
        surfaceVariant = Color(0xFF16232B),
        onSurfaceVariant = Color(0xFFB5C7D1),
        surfaceTint = Color(0xFF2EE59D),
        inverseSurface = Color(0xFFE2ECF1),
        inverseOnSurface = Color(0xFF0E161B),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF6E8491),
        outlineVariant = Color(0xFF283B47),
        surfaceContainerLowest = Color(0xFF091014),
        surfaceContainerLow = Color(0xFF121D24),
        surfaceContainer = Color(0xFF16232B),
        surfaceContainerHigh = Color(0xFF1D2D36),
        surfaceContainerHighest = Color(0xFF243742),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF007E50),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB6F9DA),
        onPrimaryContainer = Color(0xFF002817),
        inversePrimary = Color(0xFF2EE59D),
        secondary = Color(0xFF6D4AFF),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8E0FF),
        onSecondaryContainer = Color(0xFF220D66),
        tertiary = Color(0xFF0277BD),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFCDEEFF),
        onTertiaryContainer = Color(0xFF00253D),
        background = Color(0xFFF4FAFB),
        onBackground = Color(0xFF141E24),
        surface = Color(0xFFF4FAFB),
        onSurface = Color(0xFF141E24),
        surfaceVariant = Color(0xFFE3F0F4),
        onSurfaceVariant = Color(0xFF3E4F58),
        surfaceTint = Color(0xFF007E50),
        inverseSurface = Color(0xFF27343B),
        inverseOnSurface = Color(0xFFEBF4F7),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF6B7F8A),
        outlineVariant = Color(0xFFC0D3DA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFECF6F8),
        surfaceContainer = Color(0xFFE3F0F4),
        surfaceContainerHigh = Color(0xFFD9EAEF),
        surfaceContainerHighest = Color(0xFFD0E4EA),
    )
}
