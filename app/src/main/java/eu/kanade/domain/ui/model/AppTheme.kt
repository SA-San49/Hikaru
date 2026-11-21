package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

enum class AppTheme(val titleRes: StringResource?) {
    DEFAULT(MR.strings.label_default),
    MONET(MR.strings.theme_monet),
    CUSTOM(KMR.strings.theme_custom),
    SLATE_MIST(MR.strings.theme_slate_mist),
    CYBERPUNK(MR.strings.theme_cyberpunk),
    AURORA(MR.strings.theme_aurora),
    SAKURA(MR.strings.theme_sakura),
    CRIMSON_NOIR(MR.strings.theme_crimson_noir),
    CLOUDFLARE(MR.strings.theme_cloudflare),
    COTTONCANDY(MR.strings.theme_cottoncandy),
    DOOM(MR.strings.theme_doom),
    GREEN_APPLE(MR.strings.theme_greenapple),
    LAVENDER(MR.strings.theme_lavender),
    MATRIX(MR.strings.theme_matrix),
    MIDNIGHT_DUSK(MR.strings.theme_midnightdusk),
    MOCHA(MR.strings.theme_mocha),
    SAPPHIRE(MR.strings.theme_sapphire),
    NORD(MR.strings.theme_nord),
    STRAWBERRY_DAIQUIRI(MR.strings.theme_strawberrydaiquiri),
    TAKO(MR.strings.theme_tako),
    TEALTURQUOISE(MR.strings.theme_tealturquoise),
    TIDAL_WAVE(MR.strings.theme_tidalwave),
    YINYANG(MR.strings.theme_yinyang),
    YOTSUBA(MR.strings.theme_yotsuba),
    GOLDEN(KMR.strings.theme_golden),
    MONOCHROME(KMR.strings.theme_monochrome),

    // Deprecated
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),
}
