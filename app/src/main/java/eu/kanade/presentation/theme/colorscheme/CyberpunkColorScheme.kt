package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Cyberpunk theme
 * Key colors: Neon Cyan #00E5FF, Neon Magenta #FF2A85, Acid Lime #B8FF3C
 */
internal object CyberpunkColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF00E5FF),
        onPrimary = Color(0xFF00242B),
        primaryContainer = Color(0xFF004D5C),
        onPrimaryContainer = Color(0xFFB3F7FF),
        inversePrimary = Color(0xFF007B8C),
        secondary = Color(0xFFFF2A85),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF5E0D33),
        onSecondaryContainer = Color(0xFFFFD6E5),
        tertiary = Color(0xFFB8FF3C),
        onTertiary = Color(0xFF1E2D00),
        tertiaryContainer = Color(0xFF395400),
        onTertiaryContainer = Color(0xFFE4FFAD),
        background = Color(0xFF0E0B1A),
        onBackground = Color(0xFFECEAF6),
        surface = Color(0xFF0E0B1A),
        onSurface = Color(0xFFECEAF6),
        surfaceVariant = Color(0xFF1A1430),
        onSurfaceVariant = Color(0xFFC5BEDB),
        surfaceTint = Color(0xFF00E5FF),
        inverseSurface = Color(0xFFECEAF6),
        inverseOnSurface = Color(0xFF0E0B1A),
        error = Color(0xFFFF5C7C),
        onError = Color(0xFF3D0011),
        errorContainer = Color(0xFF7A0023),
        onErrorContainer = Color(0xFFFFD9E0),
        outline = Color(0xFF7B70A3),
        outlineVariant = Color(0xFF332A54),
        surfaceContainerLowest = Color(0xFF0A0814),
        surfaceContainerLow = Color(0xFF141026),
        surfaceContainer = Color(0xFF1A1430),
        surfaceContainerHigh = Color(0xFF221B3D),
        surfaceContainerHighest = Color(0xFF2B224B),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF007A8C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB8F5FF),
        onPrimaryContainer = Color(0xFF00252B),
        inversePrimary = Color(0xFF00E5FF),
        secondary = Color(0xFFD81B6B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD9E6),
        onSecondaryContainer = Color(0xFF3E001C),
        tertiary = Color(0xFF557D00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD9F799),
        onTertiaryContainer = Color(0xFF172600),
        background = Color(0xFFF7F6FC),
        onBackground = Color(0xFF191627),
        surface = Color(0xFFF7F6FC),
        onSurface = Color(0xFF191627),
        surfaceVariant = Color(0xFFEAE6F7),
        onSurfaceVariant = Color(0xFF49435E),
        surfaceTint = Color(0xFF007A8C),
        inverseSurface = Color(0xFF2C273D),
        inverseOnSurface = Color(0xFFF4F2FA),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF787190),
        outlineVariant = Color(0xFFC9C2DE),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1EEF9),
        surfaceContainer = Color(0xFFEAE6F7),
        surfaceContainerHigh = Color(0xFFE2DCF3),
        surfaceContainerHighest = Color(0xFFDAD3EE),
    )
}
