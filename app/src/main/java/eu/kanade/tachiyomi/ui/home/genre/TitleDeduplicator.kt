package eu.kanade.tachiyomi.ui.home.genre

import tachiyomi.domain.anime.model.Anime

object TitleDeduplicator {
    private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
    private val SPACES_REGEX = Regex("\\s+")

    fun normalizeTitle(title: String): String {
        return title
            .trim()
            .lowercase()
            .replace(PUNCTUATION_REGEX, "")
            .replace(SPACES_REGEX, " ")
            .trim()
    }

    fun dedupeAnime(items: List<Anime>): List<Anime> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Anime>()
        for (item in items) {
            val key = normalizeTitle(item.title)
            val effectiveKey = if (key.isBlank()) item.title.trim().lowercase() else key
            if (seen.add(effectiveKey)) {
                result.add(item)
            }
        }
        return result
    }
}
