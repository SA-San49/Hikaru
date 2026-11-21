package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Slate Mist theme
 * Key color: Slate Gray #708090
 */
internal object SlateMistColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF90A4B8),
        onPrimary = Color(0xFF10202E),
        primaryContainer = Color(0xFF334454),
        onPrimaryContainer = Color(0xFFD9E6F2),
        inversePrimary = Color(0xFF516475),
        secondary = Color(0xFF708090),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF2B3844),
        onSecondaryContainer = Color(0xFFD6E3EF),
        tertiary = Color(0xFF7CC4FA),
        onTertiary = Color(0xFF06253A),
        tertiaryContainer = Color(0xFF1B4563),
        onTertiaryContainer = Color(0xFFCDE9FF),
        background = Color(0xFF121519),
        onBackground = Color(0xFFE3E7EC),
        surface = Color(0xFF121519),
        onSurface = Color(0xFFE3E7EC),
        surfaceVariant = Color(0xFF1F242B),
        onSurfaceVariant = Color(0xFFBCC4CE),
        surfaceTint = Color(0xFF708090),
        inverseSurface = Color(0xFFE3E7EC),
        inverseOnSurface = Color(0xFF121519),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF708090),
        outlineVariant = Color(0xFF353D47),
        surfaceContainerLowest = Color(0xFF0E1114),
        surfaceContainerLow = Color(0xFF181C22),
        surfaceContainer = Color(0xFF1D2229),
        surfaceContainerHigh = Color(0xFF242A32),
        surfaceContainerHighest = Color(0xFF2C333D),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF536576),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E3F0),
        onPrimaryContainer = Color(0xFF12202C),
        inversePrimary = Color(0xFF90A4B8),
        secondary = Color(0xFF708090),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDFE7EF),
        onSecondaryContainer = Color(0xFF1D2A36),
        tertiary = Color(0xFF2B6CB0),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD3E7FF),
        onTertiaryContainer = Color(0xFF082340),
        background = Color(0xFFF6F8FA),
        onBackground = Color(0xFF181C20),
        surface = Color(0xFFF6F8FA),
        onSurface = Color(0xFF181C20),
        surfaceVariant = Color(0xFFE9EEF3),
        onSurfaceVariant = Color(0xFF454E58),
        surfaceTint = Color(0xFF708090),
        inverseSurface = Color(0xFF2D333A),
        inverseOnSurface = Color(0xFFF0F4F8),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF708090),
        outlineVariant = Color(0xFFC8D0D8),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F3F7),
        surfaceContainer = Color(0xFFE9EEF3),
        surfaceContainerHigh = Color(0xFFE2E8EE),
        surfaceContainerHighest = Color(0xFFDBE2E9),
    )
}
