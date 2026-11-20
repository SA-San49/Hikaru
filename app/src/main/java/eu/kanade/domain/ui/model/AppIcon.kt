package eu.kanade.domain.ui.model

import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.R

enum class AppIcon(
    val title: String,
    val activityName: String,
    @DrawableRes val iconResource: Int,
) {
    DEFAULT("Default", ".MainActivityDefault", R.drawable.app_icon_default),
    DRAGON("Dragon", ".MainActivityDragon", R.drawable.app_icon_dragon),
    MOON_TREE("Moon Tree", ".MainActivityMoonTree", R.drawable.app_icon_moon_tree),
    CRIMSON_TORII("Crimson Torii", ".MainActivityCrimsonTorii", R.drawable.app_icon_crimson_torii),
    BLOOD_MOON("Blood Moon", ".MainActivityBloodMoonGirl", R.drawable.app_icon_blood_moon_girl),
    SAKURA_LANTERN("Sakura Lantern", ".MainActivitySakuraLantern", R.drawable.app_icon_sakura_lantern),
    ENSO_SAKURA("Enso Sakura", ".MainActivityEnsoSakura", R.drawable.app_icon_enso_sakura),
    VIOLET_TWILIGHT("Violet Twilight", ".MainActivityVioletTwilight", R.drawable.app_icon_violet_twilight),
}
