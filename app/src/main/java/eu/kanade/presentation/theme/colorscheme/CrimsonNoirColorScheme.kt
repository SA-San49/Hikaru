package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Crimson Noir theme
 * Key colors: Velvet Crimson #E63946, Champagne Gold #E9C46A, Ice Blue #4CC9F0
 */
internal object CrimsonNoirColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFF5964),
        onPrimary = Color(0xFF3D0008),
        primaryContainer = Color(0xFF7A1220),
        onPrimaryContainer = Color(0xFFFFDADE),
        inversePrimary = Color(0xFFC1121F),
        secondary = Color(0xFFE9C46A),
        onSecondary = Color(0xFF362700),
        secondaryContainer = Color(0xFF523C08),
        onSecondaryContainer = Color(0xFFFFEAB5),
        tertiary = Color(0xFF4CC9F0),
        onTertiary = Color(0xFF002633),
        tertiaryContainer = Color(0xFF0A4A5E),
        onTertiaryContainer = Color(0xFFC7F2FF),
        background = Color(0xFF140F11),
        onBackground = Color(0xFFF2E8EA),
        surface = Color(0xFF140F11),
        onSurface = Color(0xFFF2E8EA),
        surfaceVariant = Color(0xFF22181B),
        onSurfaceVariant = Color(0xFFD4BFC4),
        surfaceTint = Color(0xFFFF5964),
        inverseSurface = Color(0xFFF2E8EA),
        inverseOnSurface = Color(0xFF140F11),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF998086),
        outlineVariant = Color(0xFF423035),
        surfaceContainerLowest = Color(0xFF0F0A0C),
        surfaceContainerLow = Color(0xFF1B1316),
        surfaceContainer = Color(0xFF22181B),
        surfaceContainerHigh = Color(0xFF2A1E22),
        surfaceContainerHighest = Color(0xFF33252A),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFC1121F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD9),
        onPrimaryContainer = Color(0xFF410004),
        inversePrimary = Color(0xFFFF5964),
        secondary = Color(0xFF996F12),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFECC2),
        onSecondaryContainer = Color(0xFF2E1E00),
        tertiary = Color(0xFF00779E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC7F0FF),
        onTertiaryContainer = Color(0xFF002330),
        background = Color(0xFFFFF8F8),
        onBackground = Color(0xFF211719),
        surface = Color(0xFFFFF8F8),
        onSurface = Color(0xFF211719),
        surfaceVariant = Color(0xFFFAEAEC),
        onSurfaceVariant = Color(0xFF554245),
        surfaceTint = Color(0xFFC1121F),
        inverseSurface = Color(0xFF362A2C),
        inverseOnSurface = Color(0xFFFCEEEF),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF877074),
        outlineVariant = Color(0xFFDDC6C9),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFDF0F1),
        surfaceContainer = Color(0xFFFAEAEC),
        surfaceContainerHigh = Color(0xFFF4E1E4),
        surfaceContainerHighest = Color(0xFFEDD8DB),
    )
}
