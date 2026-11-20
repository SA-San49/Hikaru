package eu.kanade.domain.ui.model

import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.R

private const val ACCENT_DEFAULT = 0xFF8AB4F8
private const val ACCENT_DRAGON = 0xFFE2E8F0
private const val ACCENT_MOON_TREE = 0xFF818CF8
private const val ACCENT_CRIMSON_TORII = 0xFFFF3B5C
private const val ACCENT_BLOOD_MOON = 0xFFEF4444
private const val ACCENT_SAKURA_LANTERN = 0xFFF472B6
private const val ACCENT_ENSO_SAKURA = 0xFFFDA4AF
private const val ACCENT_VIOLET_TWILIGHT = 0xFFA855F7

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
    DRAGON(
        title = "Dragon",
        styleTag = "Monochrome",
        accentHex = ACCENT_DRAGON,
        componentClassName = "eu.kanade.tachiyomi.MainActivityDragon",
        iconResource = R.drawable.app_icon_dragon,
    ),
    MOON_TREE(
        title = "Moon Tree",
        styleTag = "Celestial",
        accentHex = ACCENT_MOON_TREE,
        componentClassName = "eu.kanade.tachiyomi.MainActivityMoonTree",
        iconResource = R.drawable.app_icon_moon_tree,
    ),
    CRIMSON_TORII(
        title = "Crimson Torii",
        styleTag = "Crimson",
        accentHex = ACCENT_CRIMSON_TORII,
        componentClassName = "eu.kanade.tachiyomi.MainActivityCrimsonTorii",
        iconResource = R.drawable.app_icon_crimson_torii,
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
}
