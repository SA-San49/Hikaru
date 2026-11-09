package eu.kanade.tachiyomi.data.coil

import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.library.service.LibraryPreferences
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import uy.kohesive.injekt.injectLazy

/** Persists lightweight cover metadata so library cards do not need to rediscover it each launch. */
object AnimeCoverMetadata {
    private val libraryPreferences: LibraryPreferences by injectLazy()

    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return

        val ratios = libraryPreferences.coverRatios().get().mapNotNull { entry ->
            val separator = entry.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            val animeId = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val ratio = entry.substring(separator + 1)
                .toFloatOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: return@mapNotNull null
            animeId to ratio
        }.toMap()

        val colors = libraryPreferences.coverColors().get().mapNotNull { entry ->
            val parts = entry.split('|')
            val animeId = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val color = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            animeId to color
        }.toMap()

        AnimeCover.favoriteCoverRatioMap.clear()
        AnimeCover.favoriteCoverRatioMap.putAll(ratios)
        AnimeCover.coverRatioMap.clear()
        AnimeCover.coverRatioMap.putAll(ratios)
        AnimeCover.favoriteVibrantCoverColorMap.clear()
        AnimeCover.favoriteVibrantCoverColorMap.putAll(colors)
        AnimeCover.vibrantCoverColorMap.putAll(colors)
        CoverColorObserver.restore(ratios, colors)
        loaded = true
    }

    fun save() {
        if (!loaded) return

        val ratios = AnimeCover.favoriteCoverRatioMap.entries
            .mapTo(HashSet()) { (animeId, ratio) -> "$animeId|$ratio" }
        val colors = AnimeCover.favoriteVibrantCoverColorMap.entries
            .mapTo(HashSet()) { (animeId, color) -> "$animeId|$color" }

        libraryPreferences.coverRatios().set(ratios)
        libraryPreferences.coverColors().set(colors)
    }
}
