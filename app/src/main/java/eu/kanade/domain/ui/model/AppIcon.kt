package eu.kanade.domain.ui.model

import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.R

private const val ACCENT_DEFAULT = 0xFF8AB4F8
private const val ACCENT_BLOOD_MOON = 0xFFEF4444
private const val ACCENT_SAKURA_LANTERN = 0xFFF472B6
private const val ACCENT_ENSO_SAKURA = 0xFFFDA4AF
private const val ACCENT_VIOLET_TWILIGHT = 0xFFA855F7
private const val ACCENT_MOONLIT_CAT = 0xFF38BDF8

enum class AppIcon(
    val title: String,
    val styleTag: String,
    val accentHex: Long,
    val componentClassName: String,
    @DrawableRes val iconResource: Int,
) {
    DEFAULT(
        title = "Default",
        styleTag = "Classic",
        accentHex = ACCENT_DEFAULT,
        componentClassName = "eu.kanade.tachiyomi.MainActivityDefault",
        iconResource = R.drawable.app_icon_default,
    ),
    BLOOD_MOON(
        title = "Blood Moon",
        styleTag = "Eclipse",
        accentHex = ACCENT_BLOOD_MOON,
        componentClassName = "eu.kanade.tachiyomi.MainActivityBloodMoonGirl",
        iconResource = R.drawable.app_icon_blood_moon_girl,
    ),
    SAKURA_LANTERN(
        title = "Sakura Lantern",
        styleTag = "Blossom",
        accentHex = ACCENT_SAKURA_LANTERN,
        componentClassName = "eu.kanade.tachiyomi.MainActivitySakuraLantern",
        iconResource = R.drawable.app_icon_sakura_lantern,
    ),
    ENSO_SAKURA(
        title = "Enso Sakura",
        styleTag = "Zen Ink",
        accentHex = ACCENT_ENSO_SAKURA,
        componentClassName = "eu.kanade.tachiyomi.MainActivityEnsoSakura",
        iconResource = R.drawable.app_icon_enso_sakura,
    ),
    VIOLET_TWILIGHT(
        title = "Violet Twilight",
        styleTag = "Twilight",
        accentHex = ACCENT_VIOLET_TWILIGHT,
        componentClassName = "eu.kanade.tachiyomi.MainActivityVioletTwilight",
        iconResource = R.drawable.app_icon_violet_twilight,
    ),
    MOONLIT_CAT(
        title = "Moonlit Cat",
        styleTag = "Azure Moon",
        accentHex = ACCENT_MOONLIT_CAT,
        componentClassName = "eu.kanade.tachiyomi.MainActivityMoonlitCat",
        iconResource = R.drawable.app_icon_moonlit_cat,
    ),
}
